package dev.jeddy.samples.iso;

import dev.jeddy.app.AppConfig;
import dev.jeddy.app.JeddyApp;
import dev.jeddy.asset.Vfs;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.window.VSync;
import dev.jeddy.window.WindowConfig;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Isometric integration demo and performance scene (TASKS M10): a six-member party in a KayKit
 * crypt full of skeletons, with formations, box selection, fireballs, fog of war and a turn-based
 * movement overlay; {@code --perf} turns it into the automated performance capture. Run it with
 * {@code ./gradlew :iso-demo:run} (cooks and packs the assets first); see {@link DemoOptions} for
 * the command line and the controls.
 *
 * <p>Thread ownership: {@code main} runs on the SDL main thread, which becomes the render thread.
 */
public final class IsoDemo {
    private IsoDemo() {}

    /**
     * Starts the demo.
     *
     * @param args see {@link DemoOptions}
     */
    public static void main(String[] args) throws IOException {
        DemoOptions options = DemoOptions.parse(args);
        Vfs vfs = mount(options.assets);
        RenderPackets packets = new RenderPackets(8192);
        DemoHud hud = new DemoHud();
        PerfCapture perf =
                new PerfCapture(
                        options.perf() ? 1 << 18 : 1, options.headless, options.inlineBehaviours);
        if (options.perf()) {
            // Tells JIT warm-up from engine allocation in the report (D70).
            Path jit = options.report.resolve("jit.log");
            perf.jitLog = JitLog.start(jit) ? jit : null;
        }
        DemoRenderer renderer = new DemoRenderer(vfs, packets, options, hud, perf);
        DemoGame game = new DemoGame(options, packets, renderer, hud, perf);
        AppConfig config =
                new AppConfig()
                        .app("Jeddy iso demo", "0.1.0", "dev.jeddy.samples.iso")
                        .window(
                                new WindowConfig()
                                        .title("Jeddy – crypt (iso demo)")
                                        .size(options.width, options.height)
                                        .openGl(!options.headless)
                                        .hidden(options.headless)
                                        .vsync(options.vsync ? VSync.ADAPTIVE : VSync.OFF))
                        // Headless frames are paced like a 60 Hz display; rendered perf runs are
                        // uncapped.
                        .frameCaps(options.headless ? 60 : options.perf() ? 0 : 240, 20)
                        .renderer(renderer);
        int code = JeddyApp.run(config, game);
        vfs.close();
        if (options.perf()) {
            if (perf.hasData()) {
                PerfCapture.Report report =
                        perf.write(
                                options.report,
                                renderer.gpu(),
                                Runtime.version() + " " + System.getProperty("java.vm.name"));
                System.out.println(report.markdown());
                System.out.println("perf report: " + options.report.toAbsolutePath());
                for (String key : report.failed()) {
                    if (options.failOn.contains(key)) {
                        System.err.println("perf: budget '" + key + "' failed");
                        code = Math.max(code, 4);
                    }
                }
            } else {
                System.err.println("perf: nothing measured");
                code = Math.max(code, 2);
            }
        }
        if (game.failed()) {
            code = Math.max(code, 3);
        }
        System.exit(code);
    }

    /** Mounts every pak in {@code dir}, or the directory itself as a loose cooked tree. */
    private static Vfs mount(Path dir) throws IOException {
        Vfs vfs = new Vfs();
        vfs.mountContent(dir);
        return vfs;
    }
}
