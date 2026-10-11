package dev.jeddy.app.world;

import dev.jeddy.app.FrameRenderer;
import dev.jeddy.app.MainContext;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.Vfs;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.gl.GLCapabilities;
import dev.jeddy.gl.GLLoader;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.render.SceneRenderer;
import dev.jeddy.render.debug.DebugDraw;
import dev.jeddy.render.debug.DebugRenderer;
import dev.jeddy.render.debug.Overlay;
import dev.jeddy.render.gpu.FrameRing;
import dev.jeddy.render.gpu.GpuDevice;
import dev.jeddy.render.gpu.UploadRing;
import dev.jeddy.render.material.GpuTexture;
import dev.jeddy.render.material.MaterialAssets;
import dev.jeddy.render.mesh.MeshAssets;
import dev.jeddy.render.mesh.MeshRegistry;
import dev.jeddy.render.shader.EngineShaders;
import dev.jeddy.render.shader.ProgramBinaryCache;
import dev.jeddy.render.shader.ProgramCache;

import java.nio.file.Path;

/**
 * The main-thread half of a game built on {@link World} (TASKS M10-02): creates the GL device, mega
 * buffers, shader cache and {@link SceneRenderer} once the window exists, opens the asset manager
 * over a {@link Vfs} with the mesh, material and texture types, pumps asset uploads within a time
 * budget each frame, wires mesh materials, applies a cooked environment cube, and renders the
 * render packet the game thread extracted, then its debug lines and a screen-space {@link Hud} on
 * top.
 *
 * <p>The asset manager's owner is the game thread: it calls {@code assets().update()} every frame.
 * Types are registered here, before the game thread starts.
 *
 * <p>Thread ownership: {@link FrameRenderer} methods on the main thread; the accessors are safe
 * from the game thread after {@link #init} (which happens before the game thread starts).
 */
public final class WorldRenderer implements FrameRenderer {
    private static final Logger LOG = Log.logger("render");

    /** Settings for the renderer half. */
    public static final class Config {
        SceneRenderer.Config scene = SceneRenderer.Config.DEFAULT;
        long uploadBudgetNanos = 4_000_000;
        int meshVertices = 1 << 21;
        int meshIndices = 1 << 22;
        String environment;
        int debugLinesPerThread = 1 << 14;
        Hud hud;
        int overlayGlyphs = 8192;
        Path shaderCache = defaultShaderCache();

        /** Creates the defaults. */
        public Config() {}

        /** Sets the scene renderer configuration. */
        public Config scene(SceneRenderer.Config value) {
            scene = value;
            return this;
        }

        /** Sets the per-frame time budget for GPU uploads of loaded assets. */
        public Config uploadBudget(long nanos) {
            uploadBudgetNanos = nanos;
            return this;
        }

        /** Sets the cooked environment cube ({@code .ktx2} asset path) lighting the scene. */
        public Config environment(String path) {
            environment = path;
            return this;
        }

        /**
         * Sets the screen-space overlay drawn after the scene (HUD, profiler), and its glyph
         * capacity.
         */
        public Config hud(Hud value, int maxGlyphs) {
            hud = value;
            overlayGlyphs = maxGlyphs;
            return this;
        }

        /**
         * Sets where linked shader programs are cached between runs ({@code null}: no cache). The
         * default is {@code -Djeddy.shader.cache}, else {@code jeddy/shaders} in the user's cache
         * directory ({@code %LOCALAPPDATA%}, {@code $XDG_CACHE_HOME} or {@code ~/.cache}).
         */
        public Config shaderCache(Path dir) {
            shaderCache = dir;
            return this;
        }

        /** Sets the mega buffer capacities (vertices, indices). */
        public Config meshCapacity(int vertices, int indices) {
            meshVertices = vertices;
            meshIndices = indices;
            return this;
        }
    }

    /**
     * Queues text and rectangles on top of a rendered frame (main thread, allocation-free). The
     * game hands its per-frame values over by frame index, like the packets.
     */
    @FunctionalInterface
    public interface Hud {
        /** Queues the overlay for frame {@code index}, whose packet was just drawn. */
        void draw(Overlay overlay, RenderPacket packet, int index, int width, int height);
    }

    private final Vfs vfs;
    private final Config config;
    private final RenderPackets packets;
    private GLCapabilities caps;
    private GpuDevice device;
    private UploadRing upload;
    private MeshRegistry registry;
    private ProgramCache programs;
    private SceneRenderer renderer;
    private AssetManager assets;
    private MaterialAssets materials;
    private MeshAssets meshes;
    private DebugDraw debug;
    private FrameRing overlayRing;
    private DebugRenderer lines;
    private Overlay overlay;
    private int environment;
    private boolean environmentSet;
    private int width;
    private int height;
    private long lastFrameNanos;

    /**
     * Creates the renderer half; {@code packets} are the three frames the game thread extracts
     * into, by index.
     */
    public WorldRenderer(Vfs vfs, RenderPackets packets, Config config) {
        this.vfs = vfs;
        this.packets = packets;
        this.config = config;
    }

    private static Path defaultShaderCache() {
        String explicit = System.getProperty("jeddy.shader.cache");
        if (explicit != null) {
            return explicit.isBlank() ? null : Path.of(explicit);
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, "Jeddy", "shaders");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "jeddy", "shaders");
        }
        String home = System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".cache", "jeddy", "shaders");
    }

    /** The program binary cache of D73, or {@code null} when off or unsupported by the driver. */
    private ProgramBinaryCache binaryCache() {
        if (config.shaderCache == null) {
            return null;
        }
        ProgramBinaryCache cache = new ProgramBinaryCache(config.shaderCache, caps);
        if (!cache.isSupported()) {
            LOG.info("the driver offers no program binary formats: shaders compile every run");
            return null;
        }
        LOG.info("program binaries cached in " + config.shaderCache);
        return cache;
    }

    @Override
    public void init(MainContext ctx) {
        caps = GLLoader.init(ctx.window()::glProcAddress);
        LOG.info("GL " + caps.version() + " on " + caps.renderer());
        device = new GpuDevice(caps);
        device.useReversedZ();
        upload = new UploadRing(device, 64L << 20, 32L << 20);
        registry = new MeshRegistry(device, upload, config.meshVertices, config.meshIndices);
        programs = new ProgramCache(EngineShaders.sources(), caps, binaryCache());
        renderer = new SceneRenderer(device, programs, registry, ctx.jobs(), config.scene);
        assets = new AssetManager(vfs, ctx.jobs());
        materials = new MaterialAssets(assets, device, renderer.materials());
        meshes = new MeshAssets(assets, registry, materials);
        debug = new DebugDraw(ctx.jobs().threadSlots(), config.debugLinesPerThread);
        overlayRing = new FrameRing(device, 4L << 20, 3, "overlay ring");
        lines = new DebugRenderer(device);
        overlay = new Overlay(device, config.overlayGlyphs);
        if (config.environment != null) {
            environment = materials.textures().load(config.environment);
        }
    }

    /** Returns the asset manager (the game thread owns it: {@code update()} once per frame). */
    public AssetManager assets() {
        return assets;
    }

    /** Returns the mesh asset type (for {@link World}). */
    public AssetType<dev.jeddy.render.mesh.GpuMesh> meshes() {
        return meshes.meshes();
    }

    /** Returns the material assets. */
    public MaterialAssets materials() {
        return materials;
    }

    /** Returns the debug line buffers the game thread draws into (collected at extract). */
    public DebugDraw debugDraw() {
        return debug;
    }

    /** Returns the scene renderer (main thread). */
    public SceneRenderer scene() {
        return renderer;
    }

    /** Returns the overlay the {@link Hud} draws into (main thread). */
    public Overlay overlay() {
        return overlay;
    }

    /** Returns the GL capabilities. */
    public GLCapabilities caps() {
        return caps;
    }

    /** Returns how long the last {@link #render} took on the main thread, in nanoseconds. */
    public long lastFrameNanos() {
        return lastFrameNanos;
    }

    @Override
    public void render(MainContext ctx, int index, boolean fresh) {
        long start = System.nanoTime();
        int w = ctx.window().pixelWidth();
        int h = ctx.window().pixelHeight();
        if (w != width || h != height) {
            width = w;
            height = h;
            if (w > 0 && h > 0) {
                renderer.resize(w, h);
            }
        }
        upload.beginFrame();
        assets.pumpUploads(config.uploadBudgetNanos);
        materials.update();
        meshes.update();
        upload.endFrame();
        if (!environmentSet && environment != 0 && materials.textures().isReady(environment)) {
            GpuTexture t = materials.textures().get(environment);
            if (t.sh9 != null) {
                renderer.environment().set(t.handle, t.sh9, false);
            }
            environmentSet = true;
        }
        RenderPacket p = packets.packet(index);
        if (w > 0 && h > 0) {
            renderer.render(p);
            overlayRing.beginFrame();
            lines.draw(p, device.state(), overlayRing);
            if (config.hud != null) {
                config.hud.draw(overlay, p, index, w, h);
            }
            overlay.draw(device.state(), overlayRing, w, h);
            overlayRing.endFrame();
        }
        lastFrameNanos = System.nanoTime() - start;
    }

    @Override
    public void shutdown(MainContext ctx) {
        if (assets != null) {
            assets.close();
        }
        if (overlay != null) {
            overlay.close();
        }
        if (lines != null) {
            lines.close();
        }
        if (overlayRing != null) {
            overlayRing.close();
        }
        if (renderer != null) {
            renderer.close();
        }
        if (programs != null) {
            programs.close();
        }
        if (registry != null) {
            registry.close();
        }
        if (upload != null) {
            upload.close();
        }
        if (device != null) {
            device.close();
        }
    }
}
