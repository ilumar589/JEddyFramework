package dev.jeddy.editortool;

import dev.jeddy.app.AppConfig;
import dev.jeddy.app.JeddyApp;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.cooker.CookerConfig;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.editor.LevelFiles;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.window.VSync;
import dev.jeddy.window.WindowConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The standalone level editor (TASKS M14-08, DESIGN §19): {@code jeddy-editor --project
 * samples/iso-demo/assets --level levels/crypt.jlevel}. It cooks the project into the cooked
 * directory (and keeps cooking as sources change), mounts that directory as the runtime would, and
 * opens the level in the {@code editor} library over an empty world: the same panels, gizmos and
 * commands as the editor embedded in a game, with nothing else running.
 *
 * <p>Thread ownership: the main thread (SDL); the game thread runs {@link EditorGame}.
 */
public final class EditorMain {
    private static final Logger LOG = Log.logger("editor-tool");

    private EditorMain() {}

    /**
     * Starts the editor.
     *
     * @param args see {@link EditorOptions}
     */
    public static void main(String[] args) throws IOException {
        EditorOptions options = EditorOptions.parse(args);
        if (options.level == null) {
            System.err.println("no .jlevel found under " + options.project + "; pass --level");
            System.exit(2);
        }
        Path levelFile = options.project.resolve(options.level);
        LevelData level;
        if (Files.isRegularFile(levelFile)) {
            level = LevelFiles.read(levelFile);
        } else {
            LOG.info("creating a new level at " + levelFile);
            level = new LevelData();
            level.addRule("floor*", 1, 1, 0);
            level.addRule("wall*", 30, 2, 0);
            level.addRule("prop*", 20, 3, 0);
            level.addRule("*", 0, 0, 0);
            level.addSizeClass("medium", 0.4f, 1.8f, 0.4f);
        }
        Files.createDirectories(options.cooked);
        CookerService cooker = null;
        if (options.cook) {
            CookerConfig config =
                    CookerConfig.of(options.project, options.cooked)
                            .withCache(options.cache)
                            .withShaders(options.shaderIncludes, true);
            cooker = new CookerService(config);
            cooker.start();
        }
        Vfs vfs = new Vfs();
        int mount = vfs.mountDirectory(options.cooked);
        RenderPackets packets = new RenderPackets(8192);
        EditorRenderer renderer =
                new EditorRenderer(vfs, packets, level.settings.environment, options.screenshot);
        EditorGame game =
                new EditorGame(options, renderer, packets, vfs, mount, cooker, level, levelFile);
        AppConfig config =
                new AppConfig()
                        .app("Jeddy editor", "0.1.0", "dev.jeddy.editor")
                        .window(
                                new WindowConfig()
                                        .title("Jeddy editor – " + options.level)
                                        .size(options.width, options.height)
                                        .openGl(true)
                                        .vsync(VSync.ADAPTIVE))
                        .frameCaps(120, 20)
                        .renderer(renderer);
        int code = JeddyApp.run(config, game);
        if (cooker != null) {
            cooker.close();
        }
        vfs.close();
        System.exit(code);
    }
}
