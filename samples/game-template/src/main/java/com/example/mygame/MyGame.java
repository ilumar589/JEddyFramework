package com.example.mygame;

import dev.jeddy.app.AppConfig;
import dev.jeddy.app.AppContext;
import dev.jeddy.app.Game;
import dev.jeddy.app.JeddyApp;
import dev.jeddy.app.world.World;
import dev.jeddy.app.world.WorldAssets;
import dev.jeddy.app.world.WorldRenderer;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.render.camera.IsoCameraController;
import dev.jeddy.window.WindowConfig;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Shows a cooked level and lets the player pan (screen edges), zoom (wheel) and rotate (middle
 * mouse) the camera.
 *
 * <p>Thread ownership: {@code main} runs on the main thread, which becomes the render thread; the
 * {@link Game} callbacks run on the game thread.
 */
public final class MyGame implements Game {
    private final RenderPackets packets;
    private final WorldRenderer renderer;
    private final IsoCamera camera = new IsoCamera();
    private final IsoCameraController controller = new IsoCameraController(camera);
    private World world;
    private AssetType<SceneData> scenes;
    private int level;
    private boolean spawned;

    MyGame(RenderPackets packets, WorldRenderer renderer) {
        this.packets = packets;
        this.renderer = renderer;
    }

    /** Mounts the cooked content, then runs the game until the window closes. */
    public static void main(String[] args) throws IOException {
        // build/paks under `gradle run`, <image>/paks in a packaged game.
        Vfs vfs = new Vfs();
        vfs.mountContent(Vfs.contentDirectory(Path.of("build", "paks")));
        RenderPackets packets = new RenderPackets(4096);
        WorldRenderer renderer = new WorldRenderer(vfs, packets, new WorldRenderer.Config());
        MyGame game = new MyGame(packets, renderer);
        AppConfig config =
                new AppConfig()
                        .app("My game", "0.1.0", "com.example.mygame")
                        .window(new WindowConfig().title("My game").size(1600, 900))
                        .renderer(renderer);
        System.exit(JeddyApp.run(config, game));
    }

    @Override
    public void init(AppContext ctx) {
        // The game thread owns the asset manager: register types, then load.
        scenes = renderer.assets().register("scene", WorldAssets.scenes());
        level = scenes.load("levels/yard.jscene");
        world = new World(16_384, renderer.meshes());
    }

    @Override
    public void fixedUpdate(AppContext ctx, float dt) {
        if (!spawned) {
            return;
        }
        world.beginTick();
        // Gameplay, physics and AI move entities here (world.transforms()).
        world.updateTransforms(ctx.jobs());
    }

    @Override
    public void update(AppContext ctx, float dt, float alpha) {
        renderer.assets().update();
        if (!spawned && scenes.isReady(level)) {
            world.instantiate(scenes.get(level), 0, 0, 0, 0, RenderPacket.FLAG_STATIC);
            world.updateTransforms(ctx.jobs());
            spawned = true;
        }
        var g = ctx.gestures();
        controller.pan(g.edgeScrollX(), -g.edgeScrollY());
        controller.zoom(g.zoomDelta());
        controller.rotate(g.rotateDelta() * 0.006f);
        var in = ctx.input();
        controller.update(
                dt, in.pixelHeight() > 0 ? (float) in.pixelWidth() / in.pixelHeight() : 1);
    }

    @Override
    public void extract(AppContext ctx, int index) {
        RenderPacket p = packets.packet(index);
        p.reset();
        p.frame = ctx.frame();
        p.alpha = ctx.simulation().alpha();
        p.setCamera(
                camera.view,
                camera.projection,
                camera.viewProjection,
                camera.inverseViewProjection,
                camera.frustumPlanes,
                camera.eye().x,
                camera.eye().y,
                camera.eye().z);
        p.near = camera.near;
        p.far = camera.distance * 2 + 60;
        p.orthographic = camera.orthographic;
        if (spawned) {
            world.extract(p);
        }
    }
}
