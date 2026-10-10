package dev.jeddy.bench;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLLoader;
import dev.jeddy.gl.GLShaders;
import dev.jeddy.window.Platform;
import dev.jeddy.window.VSync;
import dev.jeddy.window.Window;
import dev.jeddy.window.WindowConfig;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * Spike for TASKS M5-11 / ADR 0001: how should materials reach their textures? 256 materials, each
 * with its own 128² texture, one small quad per material, drawn three ways:
 *
 * <ul>
 *   <li>{@code bindPerDraw}: one draw per material with {@code glBindTextureUnit} in between (the
 *       classic baseline);
 *   <li>{@code textureArray}: every texture is a layer of one {@code GL_TEXTURE_2D_ARRAY}, the
 *       material's layer comes from a storage buffer, one multi-draw for everything;
 *   <li>{@code bindless}: {@code ARB_bindless_texture} handles in a storage buffer, one multi-draw
 *       (skipped when the driver lacks the extension, e.g. llvmpipe).
 * </ul>
 *
 * Each iteration ends with {@code glFinish}, so the score includes driver and GPU time. Run it on
 * each vendor's hardware: {@code ./gradlew :bench:jmh -Pjmh.includes=TextureBindingBench}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TextureBindingBench {
    private static final int MATERIALS = 256;
    private static final int SIZE = 128;

    private static final String VS =
            """
            #version 460 core
            layout(location = 0) flat out uint material;
            layout(location = 1) out vec2 uv;
            void main() {
                uint m = uint(gl_DrawID) + uint(gl_BaseInstance) + uint(gl_InstanceID);
                vec2 c = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1);
                vec2 cell = vec2(m % 16u, m / 16u) / 16.0;
                gl_Position = vec4((cell + c / 16.0) * 2.0 - 1.0, 0.0, 1.0);
                material = m;
                uv = c;
            }
            """;
    private static final String FS_BOUND =
            """
            #version 460 core
            layout(binding = 0) uniform sampler2D tex;
            layout(location = 1) in vec2 uv;
            layout(location = 0) out vec4 color;
            void main() { color = texture(tex, uv); }
            """;
    private static final String FS_ARRAY =
            """
            #version 460 core
            layout(binding = 0) uniform sampler2DArray textures;
            layout(std430, binding = 1) readonly buffer Layers { uint layer[]; };
            layout(location = 0) flat in uint material;
            layout(location = 1) in vec2 uv;
            layout(location = 0) out vec4 color;
            void main() { color = texture(textures, vec3(uv, float(layer[material]))); }
            """;
    private static final String FS_BINDLESS =
            """
            #version 460 core
            #extension GL_ARB_bindless_texture : require
            layout(std430, binding = 1) readonly buffer Handles { uvec2 handle[]; };
            layout(location = 0) flat in uint material;
            layout(location = 1) in vec2 uv;
            layout(location = 0) out vec4 color;
            void main() { color = texture(sampler2D(handle[material]), uv); }
            """;

    private Window window;
    private Arena arena;
    private boolean bindlessSupported;
    private int vao;
    private int boundProgram;
    private int arrayProgram;
    private int bindlessProgram;
    private final int[] textures = new int[MATERIALS];
    private int array;
    private int layers;
    private int handles;
    private int indirect;

    /** Creates the textures, the array, the handles and the indirect commands. */
    @Setup(Level.Trial)
    public void setup() {
        Platform.init("Jeddy texture binding spike", "0.1", "dev.jeddy.bench.textures");
        window =
                Window.create(
                        new WindowConfig()
                                .hidden(true)
                                .size(512, 512)
                                .minimumSize(16, 16)
                                .vsync(VSync.OFF));
        GLCapabilities caps = GLLoader.init(window::glProcAddress);
        bindlessSupported = caps.has("GL_ARB_bindless_texture");
        arena = Arena.ofShared();
        MemorySegment ids = arena.allocate(ValueLayout.JAVA_INT, MATERIALS);
        MemorySegment pixels = arena.allocate((long) SIZE * SIZE * 4);
        glCreateVertexArrays(1, ids);
        vao = ids.get(ValueLayout.JAVA_INT, 0);
        glCreateTextures(GL_TEXTURE_2D_ARRAY, 1, ids);
        array = ids.get(ValueLayout.JAVA_INT, 0);
        MemorySegment names = arena.allocate(ValueLayout.JAVA_INT, MATERIALS);
        glCreateTextures(GL_TEXTURE_2D, MATERIALS, names);
        glTextureStorage3D(array, 1, GL_RGBA8, SIZE, SIZE, MATERIALS);
        for (int m = 0; m < MATERIALS; m++) {
            textures[m] = names.getAtIndex(ValueLayout.JAVA_INT, m);
            pixels.fill((byte) m);
            glTextureStorage2D(textures[m], 1, GL_RGBA8, SIZE, SIZE);
            glTextureSubImage2D(
                    textures[m], 0, 0, 0, SIZE, SIZE, GL_RGBA, GL_UNSIGNED_BYTE, pixels.address());
            glTextureSubImage3D(
                    array, 0, 0, 0, m, SIZE, SIZE, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels.address());
        }
        boundProgram = GLShaders.program(VS, FS_BOUND);
        arrayProgram = GLShaders.program(VS, FS_ARRAY);
        MemorySegment layerData = arena.allocate(ValueLayout.JAVA_INT, MATERIALS);
        for (int m = 0; m < MATERIALS; m++) {
            layerData.setAtIndex(ValueLayout.JAVA_INT, m, m);
        }
        glCreateBuffers(1, ids);
        layers = ids.get(ValueLayout.JAVA_INT, 0);
        glNamedBufferStorage(layers, layerData.byteSize(), layerData, 0);
        // DrawArraysIndirectCommand: count, instanceCount, first, baseInstance (16 bytes).
        MemorySegment arrays = arena.allocate(16L * MATERIALS);
        for (int m = 0; m < MATERIALS; m++) {
            arrays.set(ValueLayout.JAVA_INT, 16L * m, 4);
            arrays.set(ValueLayout.JAVA_INT, 16L * m + 4, 1);
            arrays.set(ValueLayout.JAVA_INT, 16L * m + 12, 0);
        }
        glCreateBuffers(1, ids);
        indirect = ids.get(ValueLayout.JAVA_INT, 0);
        glNamedBufferStorage(indirect, arrays.byteSize(), arrays, 0);
        if (bindlessSupported) {
            bindlessProgram = GLShaders.program(VS, FS_BINDLESS);
            MemorySegment handleData = arena.allocate(ValueLayout.JAVA_LONG, MATERIALS);
            for (int m = 0; m < MATERIALS; m++) {
                long h = glGetTextureHandleARB(textures[m]);
                glMakeTextureHandleResidentARB(h);
                handleData.setAtIndex(ValueLayout.JAVA_LONG, m, h);
            }
            glCreateBuffers(1, ids);
            handles = ids.get(ValueLayout.JAVA_INT, 0);
            glNamedBufferStorage(handles, handleData.byteSize(), handleData, 0);
        }
        glViewport(0, 0, 512, 512);
        glBindVertexArray(vao);
    }

    /** Releases the GL objects and the window. */
    @TearDown(Level.Trial)
    public void tearDown() {
        glFinish();
        window.close();
        Platform.shutdown();
        arena.close();
    }

    /** One draw per material, rebinding the texture each time. */
    @Benchmark
    public void bindPerDraw() {
        glUseProgram(boundProgram);
        for (int m = 0; m < MATERIALS; m++) {
            glBindTextureUnit(0, textures[m]);
            glDrawArraysInstancedBaseInstance(GL_TRIANGLE_STRIP, 0, 4, 1, m);
        }
        glFinish();
    }

    /** One multi-draw; the material's layer in a 2D array comes from a storage buffer. */
    @Benchmark
    public void textureArray() {
        glUseProgram(arrayProgram);
        glBindTextureUnit(0, array);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, layers);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect);
        glMultiDrawArraysIndirect(GL_TRIANGLE_STRIP, 0L, MATERIALS, 16);
        glFinish();
    }

    /** One multi-draw; bindless handles come from a storage buffer. */
    @Benchmark
    public void bindless() {
        if (!bindlessSupported) {
            return; // nothing to measure on this driver
        }
        glUseProgram(bindlessProgram);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, handles);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirect);
        glMultiDrawArraysIndirect(GL_TRIANGLE_STRIP, 0L, MATERIALS, 16);
        glFinish();
    }
}
