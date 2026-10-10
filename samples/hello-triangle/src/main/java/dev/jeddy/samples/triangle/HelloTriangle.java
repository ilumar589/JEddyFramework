package dev.jeddy.samples.triangle;

import static dev.jeddy.gl.ffi.GL46.*;

import dev.jeddy.app.AppConfig;
import dev.jeddy.app.AppContext;
import dev.jeddy.app.FrameRenderer;
import dev.jeddy.app.Game;
import dev.jeddy.app.JeddyApp;
import dev.jeddy.app.MainContext;
import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLDebug;
import dev.jeddy.gl.GLLoader;
import dev.jeddy.gl.GLShaders;
import dev.jeddy.input.Scancode;
import dev.jeddy.window.VSync;
import dev.jeddy.window.WindowConfig;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Draws a spinning triangle with direct state access (TASKS M3-04): the acceptance check for the
 * window, GL loader and bindings on each driver. Run with {@code ./gradlew :hello-triangle:run};
 * add {@code -Djeddy.gl.debug=true} for {@code KHR_debug} output (critical linking is then off).
 *
 * <p>Thread ownership: {@code main} runs on the SDL main thread; the renderer on the main thread,
 * the game on the game thread.
 */
public final class HelloTriangle implements FrameRenderer, Game {
    private static final boolean DEBUG = Boolean.getBoolean("jeddy.gl.debug");

    /**
     * Quit after this many seconds ({@code -Djeddy.sample.quitAfterSeconds} or the {@code
     * JEDDY_SAMPLE_QUIT_AFTER} environment variable, for smoke runs in CI); 0 runs until closed.
     */
    private static final long QUIT_AFTER_NANOS = quitAfterSeconds() * 1_000_000_000L;

    private static final String VERTEX =
            """
            #version 460 core
            layout(location = 0) in vec2 position;
            layout(location = 1) in vec3 color;
            layout(location = 0) uniform float angle;
            layout(location = 1) uniform float aspect;
            out vec3 vColor;
            void main() {
                float c = cos(angle), s = sin(angle);
                vec2 p = mat2(c, s, -s, c) * position;
                gl_Position = vec4(p.x / aspect, p.y, 0.0, 1.0);
                vColor = color;
            }
            """;

    private static final String FRAGMENT =
            """
            #version 460 core
            in vec3 vColor;
            layout(location = 0) out vec4 color;
            void main() { color = vec4(pow(vColor, vec3(1.0 / 2.2)), 1.0); }
            """;

    private final Arena arena = Arena.ofShared();
    private final MemorySegment clearColor =
            arena.allocateFrom(ValueLayout.JAVA_FLOAT, 0.02f, 0.02f, 0.05f, 1f);
    private int program;
    private int vao;
    private int vbo;
    private volatile long start = System.nanoTime();

    private HelloTriangle() {}

    private static long quitAfterSeconds() {
        String env = System.getenv("JEDDY_SAMPLE_QUIT_AFTER");
        return Long.getLong(
                "jeddy.sample.quitAfterSeconds", env == null ? 0 : Long.parseLong(env.trim()));
    }

    /** Runs the sample. */
    public static void main(String[] args) {
        if (DEBUG) {
            System.setProperty("jeddy.gl.critical", "false");
        }
        HelloTriangle sample = new HelloTriangle();
        AppConfig config =
                new AppConfig()
                        .app("Jeddy hello triangle", "0.1.0", "dev.jeddy.samples.triangle")
                        .window(
                                new WindowConfig()
                                        .title("Jeddy – hello triangle")
                                        .size(960, 540)
                                        .glDebug(DEBUG)
                                        .vsync(VSync.ADAPTIVE))
                        .renderer(sample);
        System.exit(JeddyApp.run(config, sample));
    }

    @Override
    public void init(MainContext ctx) {
        GLCapabilities caps = GLLoader.init(ctx.window()::glProcAddress);
        if (DEBUG) {
            GLDebug.enable(true);
        }
        program = GLShaders.program(VERTEX, FRAGMENT);
        float[] vertices = {
            -0.6f, -0.5f, 1f, 0.2f, 0.1f,
            0.6f, -0.5f, 0.1f, 1f, 0.2f,
            0f, 0.7f, 0.2f, 0.3f, 1f
        };
        MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
        glCreateBuffers(1, id);
        vbo = id.get(ValueLayout.JAVA_INT, 0);
        glNamedBufferStorage(
                vbo, vertices.length * 4L, arena.allocateFrom(ValueLayout.JAVA_FLOAT, vertices), 0);
        glCreateVertexArrays(1, id);
        vao = id.get(ValueLayout.JAVA_INT, 0);
        glVertexArrayVertexBuffer(vao, 0, vbo, 0, 5 * 4);
        glEnableVertexArrayAttrib(vao, 0);
        glVertexArrayAttribFormat(vao, 0, 2, GL_FLOAT, false, 0);
        glVertexArrayAttribBinding(vao, 0, 0);
        glEnableVertexArrayAttrib(vao, 1);
        glVertexArrayAttribFormat(vao, 1, 3, GL_FLOAT, false, 2 * 4);
        glVertexArrayAttribBinding(vao, 1, 0);
        start = System.nanoTime();
        System.out.println("Rendering with " + caps.renderer() + " (" + caps.version() + ")");
    }

    @Override
    public void render(MainContext ctx, int index, boolean fresh) {
        int w = ctx.window().pixelWidth();
        int h = ctx.window().pixelHeight();
        glViewport(0, 0, w, h);
        glClearNamedFramebufferfv(0, GL_COLOR, 0, clearColor);
        glUseProgram(program);
        glProgramUniform1f(program, 0, (System.nanoTime() - start) * 1e-9f);
        glProgramUniform1f(program, 1, (float) w / Math.max(1, h));
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    @Override
    public void shutdown(MainContext ctx) {
        MemorySegment id = arena.allocate(ValueLayout.JAVA_INT);
        id.set(ValueLayout.JAVA_INT, 0, vbo);
        glDeleteBuffers(1, id);
        id.set(ValueLayout.JAVA_INT, 0, vao);
        glDeleteVertexArrays(1, id);
        glDeleteProgram(program);
    }

    @Override
    public void fixedUpdate(AppContext ctx, float dt) {}

    @Override
    public void update(AppContext ctx, float dt, float alpha) {
        if (ctx.input().wasKeyPressed(Scancode.ESCAPE)
                || (QUIT_AFTER_NANOS > 0 && System.nanoTime() - start > QUIT_AFTER_NANOS)) {
            ctx.quit();
        }
    }
}
