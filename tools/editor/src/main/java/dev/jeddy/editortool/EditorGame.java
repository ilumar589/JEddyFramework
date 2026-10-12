package dev.jeddy.editortool;

import dev.jeddy.app.AppContext;
import dev.jeddy.app.Game;
import dev.jeddy.app.ui.UiBridge;
import dev.jeddy.app.ui.UiFonts;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.app.world.WorldAssets;
import dev.jeddy.app.world.WorldRenderer;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetReloader;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.editor.Editor;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.ui.Theme;
import dev.jeddy.ui.Ui;
import dev.jeddy.ui.Widgets;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The game thread of the standalone editor (DESIGN §19): a world that holds nothing but the level's
 * placements, the {@link Editor} always shown over it, the level's own lighting settings for the
 * view, and the plumbing a game would have (asset manager, UI bridge and fonts, hot reload of the
 * cooked output the {@link CookerService} rewrites).
 *
 * <p>Thread ownership: the game thread.
 */
final class EditorGame implements Game {
    private static final Logger LOG = Log.logger("editor-tool");

    private final EditorOptions options;
    private final EditorRenderer renderer;
    private final RenderPackets packets;
    private final Vfs vfs;
    private final int mount;
    private final CookerService cooker;
    private final LevelData level;
    private final Path levelFile;
    private final Editor editor = new Editor();
    private final Ui ui = new Ui();
    private final Theme theme = Theme.dark();
    private final Widgets widgets = new Widgets(ui, theme);
    private UiBridge bridge;
    private UiFonts fonts;
    private AssetManager assets;
    private AssetReloader reloader;
    private AssetType<SceneData> scenes;
    private World world;
    private LevelLoader loader;
    private double time;
    private boolean announced;
    private boolean commandsRun;
    private long frames;

    EditorGame(
            EditorOptions options,
            EditorRenderer renderer,
            RenderPackets packets,
            Vfs vfs,
            int mount,
            CookerService cooker,
            LevelData level,
            Path levelFile) {
        this.options = options;
        this.renderer = renderer;
        this.packets = packets;
        this.vfs = vfs;
        this.mount = mount;
        this.cooker = cooker;
        this.level = level;
        this.levelFile = levelFile;
    }

    @Override
    public void init(AppContext ctx) {
        WorldRenderer wr = renderer.world();
        assets = wr.assets();
        scenes = assets.register("scene", WorldAssets.scenes());
        try {
            reloader = new AssetReloader(vfs, mount, assets, 150);
        } catch (IOException e) {
            LOG.warn("hot reload unavailable", e);
        }
        bridge = new UiBridge(ctx, ui);
        widgets.clipboard = bridge.clipboard();
        fonts = new UiFonts(ui, assets, wr.materials().textures());
        fonts.load("engine/fonts/inter");
        world = new World(16_384, wr.meshes());
        loader = new LevelLoader(world, scenes);
        for (int i = 0; i < 3; i++) {
            packets.packet(i).reserve(8192, 256, 16, 16, 16_384, 0);
        }
        editor.attach(world, loader);
        editor.attachAssets(vfs, scenes);
        Path rules = options.project.resolve("rules");
        if (java.nio.file.Files.isDirectory(rules)) {
            editor.attachRules(rules); // the Rules panel edits assets/rules/*.json
        }
        if (java.nio.file.Files.isRegularFile(options.components)) {
            try {
                editor.attachSchema(
                        dev.jeddy.asset.format.ComponentSchema.parse(
                                java.nio.file.Files.readAllBytes(options.components)),
                        null); // no game runs here: rows are edited, not applied
                editor.context().console().info("components " + options.components);
            } catch (java.io.IOException | RuntimeException e) {
                editor.context().console().warn("components.json: " + e.getMessage());
            }
        }
        editor.loadSettings(ctx.settings());
        loader.load(level);
        editor.open(level, levelFile);
        editor.setShown(true);
        editor.context().console().info("project " + options.project.toAbsolutePath());
        editor.context().console().info("cooked output " + options.cooked.toAbsolutePath());
        IsoCamera cam = editor.camera();
        cam.distance = 30;
        cam.pitch = (float) Math.toRadians(50);
        editor.viewport().frameSelection();
    }

    @Override
    public void fixedUpdate(AppContext ctx, float dt) {}

    @Override
    public void update(AppContext ctx, float dt, float alpha) {
        time += dt;
        assets.update();
        if (reloader != null) {
            reloader.poll();
        }
        if (fonts != null) {
            fonts.update();
        }
        if (cooker != null) {
            cooker.drain(editor.context().console()::info);
        }
        if (loader.update() && !announced) {
            announced = true;
            editor.context()
                    .console()
                    .info("level instantiated: " + world.renderableCount() + " renderables");
        }
        if (!commandsRun && loader.isReady()) {
            commandsRun = true;
            for (String c : options.commands) {
                editor.runCommand(c);
            }
        }
        bridge.beginFrame(dt);
        editor.update(ctx, ui, dt);
        world.updateTransforms(ctx.jobs());
        editor.declare(ui, widgets);
        frames++;
        if (options.frames > 0 && frames == options.frames) {
            if (options.screenshot != null) {
                renderer.requestScreenshot();
            }
        }
        if (options.frames > 0 && frames >= options.frames + 3) {
            ctx.quit();
        }
    }

    @Override
    public void extract(AppContext ctx, int index) {
        RenderPacket p = packets.packet(index);
        p.reset();
        p.frame = ctx.frame();
        p.time = (float) time;
        p.alpha = 1;
        IsoCamera cam = editor.camera();
        p.setCamera(
                cam.view,
                cam.projection,
                cam.viewProjection,
                cam.inverseViewProjection,
                cam.frustumPlanes,
                cam.eye().x,
                cam.eye().y,
                cam.eye().z);
        p.near = cam.near;
        p.far = cam.distance * 2 + 100;
        p.orthographic = cam.orthographic;
        LevelData.Settings s = level.settings;
        p.sunDirection[0] = s.sunDirection[0];
        p.sunDirection[1] = s.sunDirection[1];
        p.sunDirection[2] = s.sunDirection[2];
        p.sunColor[0] = s.sunColor[0];
        p.sunColor[1] = s.sunColor[1];
        p.sunColor[2] = s.sunColor[2];
        p.environmentDiffuse = s.environmentDiffuse;
        p.environmentSpecular = s.environmentSpecular;
        p.exposure = s.exposure;
        world.extract(p);
        int first = loader.extractLights(p, (float) time, 1);
        // The first few lights with the shadow flag get shadow maps.
        int shadows = 0;
        for (int i = 0; i < level.lightCount() && shadows < 4; i++) {
            if ((level.lightFlags(i) & LevelData.LIGHT_SHADOW) != 0) {
                p.setLightShadow(first + i, 1 + shadows++);
            }
        }
        renderer.world().debugDraw().collect(p);
        editor.extract(p);
        ui.endFrame(p.ui);
    }

    @Override
    public boolean onQuitRequested(AppContext ctx) {
        if (editor.isDirty()) {
            editor.context()
                    .console()
                    .warn("unsaved changes: save with Ctrl+S, or quit again to discard");
            if (!quitOnce) {
                quitOnce = true;
                return false;
            }
        }
        return true;
    }

    private boolean quitOnce;

    @Override
    public void shutdown(AppContext ctx) {
        editor.saveSettings(ctx.settings());
        try {
            ctx.saveSettings();
        } catch (IOException e) {
            LOG.warn("settings not saved", e);
        }
        if (reloader != null) {
            reloader.close();
        }
    }
}
