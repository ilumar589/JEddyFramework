package dev.jeddy.samples.iso;

import dev.jeddy.asset.Vfs;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line of the iso demo.
 *
 * <pre>
 *   --assets DIR          paks (*.jpak) or a cooked tree; default build/paks, then build/cooked
 *   --size WxH            window size (default 1600x900)
 *   --no-vsync            render as fast as possible
 *   --enemies N           skeletons (default 64; 294 with --perf, for 300 agents in total)
 *   --perf SECONDS        scripted performance scene: measures, writes a report, then quits
 *   --warmup SECONDS      perf: time after loading before measuring (default 10)
 *   --instances N         extra static props scattered over the floors (perf default 16000)
 *   --lights N            dynamic point lights in total (perf default 256)
 *   --report DIR          perf report directory (default build/perf)
 *   --screenshot FILE     writes a PNG of the frame shown --screenshot-at seconds after loading
 *   --screenshot-at S     (default 3)
 *   --quit-after SECONDS  quits that long after loading (also JEDDY_SAMPLE_QUIT_AFTER)
 *   --seed N              random seed of the scripted parts
 *   --no-audio            skips opening the audio device
 *   --profiler            starts with the profiler overlay on
 *   --show-ui             starts with the inventory and the pause menu open (screenshots)
 *   --headless            no GPU: CPU-only meshes, nothing drawn (game-thread perf on servers;
 *                         with SDL_VIDEO_DRIVER=dummy)
 *   --warmup-frames N     perf: also wait this many frames before measuring (default 600)
 *   --alloc-probe         perf: attribute game-thread allocation to systems in the report
 *   --quit-after-first-frame  prints the time from JVM start to the first frame, then quits
 *   --fail-on KEYS        perf: exit with code 4 if these budgets fail (comma separated: game,
 *                         animation, main, gpu, alloc, flags, gc, load)
 * </pre>
 *
 * <p>Thread ownership: parsed on the launching thread, then read-only.
 */
final class DemoOptions {
    Path assets;
    int width = 1600;
    int height = 900;
    boolean vsync = true;
    int enemies = -1;
    double perfSeconds;
    double warmupSeconds = 10;
    int instances = -1;
    int lights = -1;
    Path report = Path.of("build", "perf");
    Path screenshot;
    double screenshotAt = 3;
    double quitAfter;
    long seed = 1;
    boolean audio = true;
    boolean profiler;
    boolean showUi;
    boolean headless;
    int warmupFrames = 600;
    boolean allocProbe;

    /** Echo the combat log to the console log (scripted and headless runs). */
    boolean echoCombat;

    /** Start in turn-based mode. */
    boolean turnBased;

    /** Scripted runs: party turns end by themselves after a strike at whoever is in reach. */
    boolean autoTurns;

    boolean quitAfterFirstFrame;
    java.util.List<String> failOn = java.util.List.of();
    boolean editor;
    Path levelSource;
    java.util.List<String> editorCommands = new java.util.ArrayList<>();

    boolean perf() {
        return perfSeconds > 0;
    }

    int enemyCount() {
        return enemies >= 0 ? enemies : perf() ? 294 : 64;
    }

    int instanceCount() {
        return instances >= 0 ? instances : perf() ? 16_000 : 0;
    }

    int lightCount() {
        return lights >= 0 ? lights : perf() ? 256 : 0;
    }

    static DemoOptions parse(String[] args) {
        DemoOptions o = new DemoOptions();
        String env = System.getenv("JEDDY_SAMPLE_QUIT_AFTER");
        if (env != null && !env.isBlank()) {
            o.quitAfter = Double.parseDouble(env.trim());
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--assets" -> o.assets = Path.of(value(args, ++i, a));
                case "--size" -> {
                    String v = value(args, ++i, a);
                    int x = v.indexOf('x');
                    if (x < 0) {
                        throw new IllegalArgumentException("--size wants WxH, got " + v);
                    }
                    o.width = Integer.parseInt(v.substring(0, x));
                    o.height = Integer.parseInt(v.substring(x + 1));
                }
                case "--no-vsync" -> o.vsync = false;
                case "--enemies" -> o.enemies = Integer.parseInt(value(args, ++i, a));
                case "--perf" -> o.perfSeconds = Double.parseDouble(value(args, ++i, a));
                case "--warmup" -> o.warmupSeconds = Double.parseDouble(value(args, ++i, a));
                case "--instances" -> o.instances = Integer.parseInt(value(args, ++i, a));
                case "--lights" -> o.lights = Integer.parseInt(value(args, ++i, a));
                case "--report" -> o.report = Path.of(value(args, ++i, a));
                case "--screenshot" -> o.screenshot = Path.of(value(args, ++i, a));
                case "--screenshot-at" -> o.screenshotAt = Double.parseDouble(value(args, ++i, a));
                case "--quit-after" -> o.quitAfter = Double.parseDouble(value(args, ++i, a));
                case "--seed" -> o.seed = Long.parseLong(value(args, ++i, a));
                case "--no-audio" -> o.audio = false;
                case "--profiler" -> o.profiler = true;
                case "--show-ui" -> o.showUi = true;
                case "--headless" -> o.headless = true;
                case "--alloc-probe" -> o.allocProbe = true;
                case "--echo-combat" -> o.echoCombat = true;
                case "--turn-based" -> o.turnBased = true;
                case "--auto-turns" -> o.autoTurns = true;
                case "--quit-after-first-frame" -> o.quitAfterFirstFrame = true;
                case "--warmup-frames" -> o.warmupFrames = Integer.parseInt(value(args, ++i, a));
                case "--fail-on" -> o.failOn = java.util.List.of(value(args, ++i, a).split(","));
                case "--editor" -> o.editor = true;
                case "--editor-command" -> o.editorCommands.add(value(args, ++i, a));
                case "--level-source" -> o.levelSource = Path.of(value(args, ++i, a));
                case "--help", "-h" -> {
                    System.out.println(usage());
                    System.exit(0);
                }
                default ->
                        throw new IllegalArgumentException("unknown option " + a + "\n" + usage());
            }
        }
        if (o.perf()) {
            o.vsync = false;
        }
        if (o.assets == null) {
            o.assets = findAssets();
        }
        if (o.levelSource == null) {
            o.levelSource = findLevelSource();
        }
        return o;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    /**
     * Looks for {@code -Djeddy.assets.dir}, then the paks of an installed image ({@code
     * -Djeddy.app.dir}, set by the packaged launchers; {@link Vfs#contentDirectory}), then packed
     * and loose cooked assets of the sample or the repository root.
     */
    private static Path findAssets() {
        Path content = Vfs.contentDirectory(null);
        if (content != null) {
            return content;
        }
        for (String base : new String[] {"build", "samples/iso-demo/build"}) {
            Path paks = Path.of(base, "paks");
            if (Files.isDirectory(paks)) {
                return paks;
            }
        }
        for (String base : new String[] {"build", "samples/iso-demo/build"}) {
            Path cooked = Path.of(base, "cooked");
            if (Files.isDirectory(cooked)) {
                return cooked;
            }
        }
        throw new IllegalStateException(
                "no cooked assets found: run ./gradlew :iso-demo:packAssets, or pass --assets DIR");
    }

    /**
     * The source {@code .jlevel} the editor saves to: the sample's asset tree when running from the
     * repository, else {@code build/crypt.jlevel} next to the cooked assets.
     */
    private static Path findLevelSource() {
        for (String base : new String[] {"assets", "samples/iso-demo/assets"}) {
            Path p = Path.of(base, "levels", "crypt.jlevel");
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return Path.of("build", "crypt.jlevel");
    }

    static String usage() {
        return """
        usage: iso-demo [options]
          --assets DIR          paks (*.jpak) or a cooked tree (default build/paks, build/cooked)
          --size WxH            window size (default 1600x900)
          --no-vsync            render as fast as possible
          --enemies N           skeletons (default 64; 294 with --perf)
          --seed N              seed of the scripted parts and the dice
          --no-audio            do not open an audio device
          --turn-based          start in turn-based combat (T switches at any time)
          --auto-turns          scripted play: march on the skeletons, end turns alone
          --echo-combat         print the combat log to the console
          --profiler            start with the profiler overlay on
          --show-ui             start with the inventory and the menu open
          --screenshot FILE     PNG of the frame --screenshot-at seconds after loading
          --screenshot-at S     (default 3)
          --quit-after SECONDS  quit that long after loading
          --quit-after-first-frame
          --headless            no GPU, nothing drawn: game-thread perf on servers
          --perf SECONDS        scripted performance scene with a report, then quit
          --warmup SECONDS      perf warm-up after loading (default 10)
          --warmup-frames N     perf: also wait N frames before measuring (default 600)
          --instances N         extra static props (perf default 16000)
          --lights N            dynamic point lights in total (perf default 256)
          --report DIR          perf report directory (default build/perf)
          --alloc-probe         perf: game-thread allocation by system in the report
          --fail-on KEYS        perf: exit 4 if these budgets fail
                                (game,animation,main,gpu,alloc,flags,gc,load)
          --editor              start with the level editor open (F10 toggles it)
          --editor-command CMD  run an editor console command at start (repeatable)
          --level-source FILE   the .jlevel the editor saves (default: the sample's
                                assets/levels/crypt.jlevel, else build/crypt.jlevel)
        controls:
          left click        select a party member / move the selection
          left drag         box select          right click   move the selection
          1-6, Tab          select a member, everyone (Shift adds)
          WASD, arrows      pan                 wheel         zoom
          Q / E, middle drag rotate             C             center on the selection
          G                 cycle formations    F             fireball (click to cast)
          T                 real time / turn-based combat     Enter  end the turn
          I                 inventory           Space         pause
          F3                profiler            F12           screenshot
          F10               level editor        Esc           cancel / menu
        """;
    }
}
