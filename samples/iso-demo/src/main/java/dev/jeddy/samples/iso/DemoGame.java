package dev.jeddy.samples.iso;

import dev.jeddy.app.AppContext;
import dev.jeddy.app.Game;
import dev.jeddy.app.audio.AudioSync;
import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.World;
import dev.jeddy.app.world.WorldAssets;
import dev.jeddy.asset.AssetLoaders;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetState;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.audio.Audio;
import dev.jeddy.audio.SoundClip;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.input.CrpgGestures;
import dev.jeddy.input.Input;
import dev.jeddy.input.Scancode;
import dev.jeddy.math.IsoCamera;
import dev.jeddy.math.MathF;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.bvh.Bvh;
import dev.jeddy.physics.debug.DebugSink;
import dev.jeddy.physics.nav.NavMesh;
import dev.jeddy.physics.nav.NavMeshData;
import dev.jeddy.physics.nav.TurnBasedNav;
import dev.jeddy.physics.query.CollisionWorld;
import dev.jeddy.physics.query.QueryContext;
import dev.jeddy.physics.vis.OcclusionGrid;
import dev.jeddy.render.RenderPacket;
import dev.jeddy.render.RenderPackets;
import dev.jeddy.render.camera.IsoCameraController;

/**
 * The iso demo's game thread (TASKS M10-03 to M10-05): loads the crypt and the characters, then
 * runs the party, the skeletons, fireballs, fog of war, the turn-based movement overlay and the
 * camera, and extracts each frame into a render packet. With {@code --perf} a script plays it
 * (formation tours, wandering skeletons, a fireball every 1.5 s, an orbiting camera) while {@link
 * PerfCapture} measures.
 *
 * <p>Thread ownership: the game thread ({@link Game} callbacks). Allocates nothing per frame once
 * loaded.
 */
final class DemoGame implements Game {
    private static final Logger LOG = Log.logger("iso-demo");

    /** Movement allowance of one turn in the turn-based overlay, meters. */
    private static final float TURN_MOVEMENT = 9;

    /** Scripted tour of the crypt (x, z pairs) for --perf. */
    private static final float[] TOUR = {
        30, 14, 52, 10, 70, 14, 66, 40, 44, 44, 22, 42, 10, 40, 12, 14
    };

    private final DemoOptions options;
    private final RenderPackets packets;
    private final DemoRenderer renderer;
    private final DemoHud hud;
    private final PerfCapture perf;

    private AssetType<SceneData> scenes;
    private AssetType<Bvh> collisionAssets;
    private AssetType<NavMeshData> navAssets;
    private AssetType<OcclusionGrid> occlusionAssets;
    private AssetType<byte[]> files;
    private AssetType<SoundClip> sounds;
    private int levelScene;
    private int levelCollision;
    private int levelNav;
    private int levelOcclusion;
    private int levelSpawns;
    private final int[] partyModels = new int[Characters.PARTY.length];
    private final int[] enemyModels = new int[Characters.ENEMIES.length];
    private int boomSound;
    private int ambienceSound;
    private boolean ambienceStarted;

    private World world;
    private Level level;
    private Party party;
    private Enemies enemies;
    private Spells spells;
    private AgentSync sync;
    private Audio audio;
    private AudioSync audioSync;
    private TurnBasedNav turnBased;
    private DebugSink debugSink;
    private int debugThread;

    private final IsoCamera camera = new IsoCamera();
    private final IsoCameraController controller = new IsoCameraController(camera);
    private boolean following;
    private QueryContext pickQuery = new QueryContext();
    private final float[] rayOrigin = new float[3];
    private final float[] rayDirection = new float[3];
    private final float[] center = new float[3];
    private final float[] scratch = new float[3];
    private boolean groundValid;
    private float groundX;
    private float groundY;
    private float groundZ;
    private float markerX;
    private float markerY;
    private float markerZ;
    private float markerAge = 99;

    private boolean turnBasedOn;
    private final int[] boundary = new int[2048];
    private int boundaryCount;
    private int floodMember = -1;
    private float floodX;
    private float floodZ;
    private float moveCost;
    private boolean profiler;
    private boolean screenshotRequested;
    private boolean screenshotTaken;

    private final Rng rng;
    private double time;
    private long startNanos;
    private long loadedNanos;
    private double loadedTime = -1;
    private boolean levelBuilt;
    private boolean ready;
    private boolean failed;
    private long frameStart;
    private long lastGameNanos;
    private int instanceCount;

    // Perf script.
    private int tourIndex;
    private float tourTimer;
    private float castTimer;
    private boolean measuring;
    private double measureStart;
    private long framesSinceReady;
    private final AllocProbe probe = new AllocProbe();

    DemoGame(
            DemoOptions options,
            RenderPackets packets,
            DemoRenderer renderer,
            DemoHud hud,
            PerfCapture perf) {
        this.options = options;
        this.packets = packets;
        this.renderer = renderer;
        this.hud = hud;
        this.perf = perf;
        this.rng = new Rng(options.seed);
        this.profiler = options.profiler;
    }

    /** Whether loading failed or never finished (the process exits with an error). */
    boolean failed() {
        return failed;
    }

    @Override
    public void init(AppContext ctx) {
        startNanos = System.nanoTime();
        AssetManager assets = renderer.assets();
        scenes = assets.register("scene", WorldAssets.scenes());
        collisionAssets = assets.register("collision", WorldAssets.collision());
        navAssets = assets.register("navmesh", WorldAssets.navmeshes());
        occlusionAssets = assets.register("occlusion", WorldAssets.occlusion());
        files = assets.register("file", AssetLoaders.bytes());
        levelScene = scenes.load(Level.SCENE);
        levelCollision = collisionAssets.load(Level.COLLISION);
        levelNav = navAssets.load(Level.NAVMESH);
        levelOcclusion = occlusionAssets.load(Level.OCCLUSION);
        levelSpawns = files.load(Level.SPAWNS);
        for (int i = 0; i < partyModels.length; i++) {
            partyModels[i] = scenes.load(Characters.path(Characters.PARTY[i]));
        }
        for (int i = 0; i < enemyModels.length; i++) {
            enemyModels[i] = scenes.load(Characters.path(Characters.ENEMIES[i]));
        }
        audio = renderer.audio();
        if (audio != null) {
            sounds = assets.register("sound", audio.loader());
            boomSound = sounds.load("audio/fireball.wav");
            ambienceSound = sounds.load("audio/crypt_ambience.wav");
        }
        int characters = Party.SIZE + options.enemyCount();
        world = new World(8192 + characters * 16 + options.instanceCount(), renderer.meshes());
        // Size the packets for the peak up front, so nothing grows mid-game (DESIGN §4.2).
        for (int i = 0; i < DemoHud.FRAMES; i++) {
            packets.packet(i)
                    .reserve(
                            4096 + characters * 12 + options.instanceCount(),
                            options.lightCount() + 64 + Spells.MAX,
                            64,
                            8 + Spells.MAX * 3,
                            16_384);
        }
        debugSink = renderer.debugDraw()::line;
        debugThread = ctx.jobs().threadIndex();
        camera.distance = options.perf() ? 30 : 22;
        camera.yaw = MathF.PI * 0.25f;
        camera.pitch = MathF.toRadians(52);
        controller.minDistance = 8;
        controller.maxDistance = 48;
        controller.smoothing = 0.15f;
        controller.zoom(0);
        perf.agents = characters;
    }

    // Loading ------------------------------------------------------------------------------------

    private boolean allReady() {
        boolean ok =
                ready(scenes, levelScene)
                        && ready(collisionAssets, levelCollision)
                        && ready(navAssets, levelNav)
                        && ready(occlusionAssets, levelOcclusion)
                        && ready(files, levelSpawns);
        for (int h : partyModels) {
            ok &= ready(scenes, h);
        }
        for (int h : enemyModels) {
            ok &= ready(scenes, h);
        }
        return ok;
    }

    private <T> boolean ready(AssetType<T> type, int handle) {
        int s = type.state(handle);
        if (s == AssetState.FAILED && !failed) {
            failed = true;
            LOG.error("failed to load " + type.name() + " asset", type.error(handle));
        }
        return s == AssetState.READY;
    }

    private void build(AppContext ctx) {
        long t0 = System.nanoTime();
        int characters = Party.SIZE + options.enemyCount();
        level =
                new Level(
                        world,
                        ctx.jobs(),
                        scenes.get(levelScene),
                        collisionAssets.get(levelCollision),
                        navAssets.get(levelNav),
                        occlusionAssets.get(levelOcclusion),
                        files.get(levelSpawns),
                        characters,
                        options.seed);
        sync = new AgentSync(level.crowd, world);
        pickQuery = new QueryContext(characters);
        SceneData[] partyScenes = new SceneData[partyModels.length];
        for (int i = 0; i < partyScenes.length; i++) {
            partyScenes[i] = scenes.get(partyModels[i]);
        }
        SceneData[] enemyScenes = new SceneData[enemyModels.length];
        for (int i = 0; i < enemyScenes.length; i++) {
            enemyScenes[i] = scenes.get(enemyModels[i]);
        }
        party = new Party(level, world, sync, partyScenes);
        party.center(false, center);
        enemies =
                new Enemies(
                        level,
                        world,
                        sync,
                        enemyScenes,
                        options.enemyCount(),
                        rng,
                        options.perf(),
                        options.perf() ? 3 : 45,
                        center[0],
                        center[2]);
        spells = new Spells(audio, characters);
        if (audio != null) {
            audioSync = new AudioSync(audio, world);
        }
        turnBased = new TurnBasedNav(level.nav, 4096);
        scatterProps(options.instanceCount());
        instanceCount = world.renderableCount();
        controller.setBounds(
                level.minX, level.minZ, level.minX + level.sizeX, level.minZ + level.sizeZ);
        controller.setGroundHeight(0);
        camera.target.set(center[0], center[1], center[2]);
        for (int i = 0; i < Party.SIZE; i++) {
            int a = party.agent[i];
            level.visibility.setViewer(i, level.crowd.x(a), level.crowd.z(a), Party.SIGHT);
        }
        level.fog.update();
        enemies.updateVisibility(level.visibility);
        world.updateTransforms(ctx.jobs());
        levelBuilt = true;
        LOG.info(
                "crypt built in "
                        + (System.nanoTime() - t0) / 1_000_000
                        + " ms: "
                        + world.renderableCount()
                        + " renderables, "
                        + characters
                        + " agents, "
                        + level.torchCount
                        + " torches");
    }

    /**
     * Scatters {@code n} static props (crates, barrels, rubble from the crypt's own meshes) over
     * the floor, for the perf scene's instance count.
     */
    private void scatterProps(int n) {
        if (n <= 0) {
            return;
        }
        SceneData s = level.scene;
        long[] props = new long[s.meshCount()];
        int count = 0;
        for (int i = 0; i < s.nodeCount(); i++) {
            int m = s.mesh(i);
            String name = s.nodeName(i);
            if (m >= 0 && (name.startsWith("prop_") || name.startsWith("decor_"))) {
                long id = s.meshId(m);
                boolean seen = false;
                for (int k = 0; k < count; k++) {
                    seen |= props[k] == id;
                }
                if (!seen) {
                    props[count++] = id;
                }
            }
        }
        if (count == 0) {
            return;
        }
        int flags = RenderPacket.FLAG_STATIC | RenderPacket.FLAG_CASTS_SHADOW;
        for (int i = 0; i < n; i++) {
            int o = rng.nextInt(level.walkableCount) * 3;
            float x = level.walkable[o] + (rng.nextFloat() - 0.5f) * 1.5f;
            float z = level.walkable[o + 2] + (rng.nextFloat() - 0.5f) * 1.5f;
            int e = world.create(0, x, level.walkable[o + 1], z, rng.nextFloat() * MathF.PI * 2);
            float scale = 0.35f + rng.nextFloat() * 0.3f;
            world.transforms().setScale(e, scale, scale, scale);
            world.transforms().setStatic(e, true);
            world.addRenderable(e, props[rng.nextInt(count)], RenderPacket.MESH_MATERIALS, flags);
        }
    }

    // Frame --------------------------------------------------------------------------------------

    @Override
    public void fixedUpdate(AppContext ctx, float dt) {
        if (frameStart == 0) {
            frameStart = System.nanoTime();
        }
        if (!levelBuilt) {
            return;
        }
        AllocProbe a = probe;
        a.mark(AllocProbe.START);
        world.beginTick();
        a.mark(AllocProbe.BEGIN_TICK);
        if (options.perf()) {
            script(dt);
        }
        a.mark(AllocProbe.SCRIPT);
        enemies.think(dt, party);
        a.mark(AllocProbe.THINK);
        party.tick();
        a.mark(AllocProbe.PARTY);
        level.crowd.update(dt);
        a.mark(AllocProbe.CROWD);
        sync.apply(dt);
        a.mark(AllocProbe.SYNC);
        level.collision.update();
        a.mark(AllocProbe.COLLISION);
        spells.tick(dt, level.collision, enemies);
        a.mark(AllocProbe.SPELLS);
        for (int i = 0; i < Party.SIZE; i++) {
            int m = party.agent[i];
            level.visibility.setViewer(i, level.crowd.x(m), level.crowd.z(m), Party.SIGHT);
        }
        level.fog.update();
        enemies.updateVisibility(level.visibility);
        a.mark(AllocProbe.VISIBILITY);
        world.updateTransforms(ctx.jobs());
        a.mark(AllocProbe.TRANSFORMS);
        markerAge += dt;
    }

    @Override
    public void update(AppContext ctx, float dt, float alpha) {
        if (frameStart == 0) {
            frameStart = System.nanoTime();
        }
        time += dt;
        probe.mark(AllocProbe.START);
        renderer.assets().update();
        probe.mark(AllocProbe.ASSETS);
        Input in = ctx.input();
        if (!levelBuilt) {
            if (allReady()) {
                build(ctx);
            } else if (failed || (options.quitAfter > 0 || options.perf()) && time > 120) {
                failed = true;
                LOG.error("the crypt did not load; quitting");
                ctx.quit();
            }
        }
        if (levelBuilt && !ready && world.loadingRenderables() == 0) {
            ready = true;
            loadedNanos = System.nanoTime() - startNanos;
            loadedTime = time;
            perf.loadNanos = loadedNanos;
            LOG.info("loaded in " + loadedNanos / 1_000_000 + " ms");
            if (audio != null && sounds.isReady(ambienceSound)) {
                audio.play(sounds.get(ambienceSound), Audio.BUS_AMBIENT, 0.6f, 10, -1, 2f, 0);
                ambienceStarted = true;
            }
        }
        if (levelBuilt) {
            if (audio != null && sounds.isReady(boomSound)) {
                spells.setSound(sounds.get(boomSound));
            }
            handleInput(ctx, in);
        }
        probe.mark(AllocProbe.INPUT);
        updateCamera(ctx, in, dt);
        probe.mark(AllocProbe.CAMERA);
        if (audio != null) {
            if (audioSync != null) {
                audioSync.update(camera);
            }
            audio.update(dt);
        }
        probe.mark(AllocProbe.AUDIO);
        if (options.quitAfterFirstFrame && renderer.firstFrameShown()) {
            ctx.quit();
        }
        if (in.wasKeyPressed(Scancode.ESCAPE)) {
            if (spells != null && spells.targeting) {
                spells.targeting = false;
            } else {
                ctx.quit();
            }
        }
        timers(ctx);
        probe.mark(AllocProbe.TIMERS);
    }

    private void timers(AppContext ctx) {
        if (loadedTime < 0) {
            return;
        }
        double since = time - loadedTime;
        if (options.screenshot != null && !screenshotTaken && since >= options.screenshotAt) {
            screenshotRequested = true;
            screenshotTaken = true;
        }
        if (options.perf()) {
            // Warm up by time, frames and gameplay (the first fireballs and their sounds link
            // code paths that then stay linked).
            if (!measuring
                    && since >= options.warmupSeconds
                    && framesSinceReady >= options.warmupFrames
                    && spells.casts >= 4
                    && measureStart == 0) {
                LOG.info("perf: measuring for " + (long) options.perfSeconds + " s");
                measuring = true;
                measureStart = time;
                perf.instances = instanceCount;
                perf.begin();
                if (options.allocProbe) {
                    probe.start();
                }
            } else if (measuring && time - measureStart >= options.perfSeconds) {
                measuring = false;
                probe.enabled = false;
                perf.end();

                if (options.allocProbe) {
                    perf.notes = probe.report();
                    perf.probeEvents = probe.secondHalfEvents();
                }
                perf.casts = spells.casts;
                ctx.quit();
            }
        }
        if (options.quitAfter > 0 && since >= options.quitAfter && !options.perf()) {
            ctx.quit();
        }
    }

    private void handleInput(AppContext ctx, Input in) {
        CrpgGestures g = ctx.gestures();
        float ww = Math.max(1, in.windowWidth());
        float wh = Math.max(1, in.windowHeight());
        boolean shift = in.isKeyDown(Scancode.LSHIFT) || in.isKeyDown(Scancode.RSHIFT);

        // What is under the cursor.
        camera.screenRay(g.x() / ww * 2 - 1, 1 - g.y() / wh * 2, rayOrigin, rayDirection);
        int kind =
                level.collision.pick(
                        rayOrigin[0],
                        rayOrigin[1],
                        rayOrigin[2],
                        rayDirection[0],
                        rayDirection[1],
                        rayDirection[2],
                        400,
                        Level.UNITS,
                        0,
                        0.3f,
                        pickQuery);
        int unit = kind == CollisionWorld.PICK_UNIT ? pickQuery.entity : -1;
        groundValid = kind == CollisionWorld.PICK_GROUND;
        if (groundValid || unit >= 0) {
            groundX = pickQuery.x;
            groundY = groundValid ? pickQuery.y : level.crowd.y(unit);
            groundZ = pickQuery.z;
        }
        party.hovered = unit >= 0 ? party.memberOf(unit) : -1;
        enemies.hovered = unit >= 0 ? enemies.enemyOf(unit) : -1;

        // Keys.
        if (in.wasKeyPressed(Scancode.SPACE)) {
            ctx.simulation().setPaused(!ctx.simulation().isPaused());
        }
        if (in.wasKeyPressed(Scancode.TAB)) {
            party.selectAll();
        }
        for (int i = 0; i < Party.SIZE; i++) {
            if (in.wasKeyPressed(Scancode.DIGIT_1 + i)) {
                party.select(i, shift);
            }
        }
        if (in.wasKeyPressed(Scancode.G)) {
            party.cycleFormation();
        }
        if (in.wasKeyPressed(Scancode.F)) {
            spells.targeting = !spells.targeting;
        }
        if (in.wasKeyPressed(Scancode.T)) {
            turnBasedOn = !turnBasedOn;
            floodMember = -1;
        }
        if (in.wasKeyPressed(Scancode.C)) {
            following = !following;
        }
        if (in.wasKeyPressed(Scancode.F3)) {
            profiler = !profiler;
        }
        if (in.wasKeyPressed(Scancode.F12)) {
            screenshotRequested = true;
        }

        // Mouse.
        spells.aimValid = groundValid || enemies.hovered >= 0;
        spells.aimX = groundX;
        spells.aimY = groundY;
        spells.aimZ = groundZ;
        if (spells.targeting) {
            if (g.selectClicked() && spells.aimValid) {
                castFireball(groundX, groundY, groundZ);
                spells.targeting = shift;
            } else if (g.commandClicked()) {
                spells.targeting = false;
            }
        } else if (g.boxFinished()) {
            party.selectBox(
                    camera.viewProjection,
                    ww,
                    wh,
                    g.boxMinX(),
                    g.boxMinY(),
                    g.boxMaxX(),
                    g.boxMaxY(),
                    shift);
        } else if (g.selectClicked()) {
            if (party.hovered >= 0) {
                party.select(party.hovered, shift);
            } else if (groundValid || enemies.hovered >= 0) {
                order(groundX, groundY, groundZ);
            }
        } else if (g.commandClicked() && (groundValid || enemies.hovered >= 0)) {
            order(groundX, groundY, groundZ);
        }
        party.updateHighlights();
        enemies.updateHighlights();

        if (turnBasedOn) {
            updateTurnBased();
        }
    }

    private void order(float x, float y, float z) {
        party.order(x, y, z);
        markerX = x;
        markerY = y;
        markerZ = z;
        markerAge = 0;
    }

    /** The party's caster (the first mage) throws a fireball at {@code (x, y, z)}. */
    private void castFireball(float x, float y, float z) {
        int a = party.agent[2];
        spells.cast(level.crowd.x(a), level.crowd.y(a), level.crowd.z(a), x, y, z);
    }

    /** Floods the leader's movement range when it moved, and prices the hovered point. */
    private void updateTurnBased() {
        int m = party.leader();
        if (m < 0) {
            boundaryCount = 0;
            return;
        }
        int a = party.agent[m];
        float x = level.crowd.x(a);
        float z = level.crowd.z(a);
        if (m != floodMember || Math.abs(x - floodX) + Math.abs(z - floodZ) > 0.05f) {
            floodMember = m;
            floodX = x;
            floodZ = z;
            int ref =
                    level.query.findNearestPoly(
                            x, level.crowd.y(a), z, 0.5f, 2f, 0.5f, level.filter, scratch);
            boundaryCount = 0;
            if (ref != NavMesh.NULL_REF) {
                turnBased.flood(
                        ref, scratch[0], scratch[1], scratch[2], TURN_MOVEMENT, level.filter);
                boundaryCount = turnBased.boundary(boundary);
            }
        }
        moveCost = -1;
        if (groundValid) {
            int ref =
                    level.query.findNearestPoly(
                            groundX, groundY, groundZ, 0.3f, 1f, 0.3f, level.filter, scratch);
            if (ref != NavMesh.NULL_REF) {
                float c = turnBased.costTo(ref, scratch[0], scratch[1], scratch[2]);
                moveCost = Float.isInfinite(c) ? -1 : c;
            }
        }
    }

    private void updateCamera(AppContext ctx, Input in, float dt) {
        CrpgGestures g = ctx.gestures();
        float right = 0;
        float forward = 0;
        if (in.isKeyDown(Scancode.W) || in.isKeyDown(Scancode.UP)) {
            forward += 1;
        }
        if (in.isKeyDown(Scancode.S) || in.isKeyDown(Scancode.DOWN)) {
            forward -= 1;
        }
        if (in.isKeyDown(Scancode.D) || in.isKeyDown(Scancode.RIGHT)) {
            right += 1;
        }
        if (in.isKeyDown(Scancode.A) || in.isKeyDown(Scancode.LEFT)) {
            right -= 1;
        }
        right += g.edgeScrollX();
        forward -= g.edgeScrollY();
        if (right != 0 || forward != 0) {
            following = false;
            controller.pan(right, forward);
        }
        if (g.zoomDelta() != 0) {
            controller.zoom(g.zoomDelta());
        }
        float rotate = g.rotateDelta() * 0.006f;
        if (in.isKeyDown(Scancode.Q)) {
            rotate -= 1.6f * dt;
        }
        if (in.isKeyDown(Scancode.E)) {
            rotate += 1.6f * dt;
        }
        if (options.perf() && levelBuilt) {
            rotate += 0.12f * dt;
            following = true;
        }
        if (rotate != 0) {
            controller.rotate(rotate);
        }
        if (following && levelBuilt) {
            party.center(!options.perf(), center);
            controller.follow(center[0], center[1], center[2]);
        }
        int pw = in.pixelWidth();
        int ph = in.pixelHeight();
        controller.update(dt, ph > 0 ? (float) pw / ph : 16f / 9f);
    }

    /** --perf: tours the crypt in formation and throws fireballs at whatever shows up. */
    private void script(float dt) {
        tourTimer -= dt;
        if (tourTimer <= 0) {
            tourTimer = 8;
            float x = TOUR[tourIndex * 2];
            float z = TOUR[tourIndex * 2 + 1];
            tourIndex = (tourIndex + 1) % (TOUR.length / 2);
            if (level.snap(x, 0, z, 3f, scratch)) {
                party.formation = tourIndex % 3;
                party.orderAll(scratch[0], scratch[1], scratch[2]);
            }
        }
        castTimer -= dt;
        if (castTimer <= 0) {
            castTimer = 1.5f;
            if (enemies.randomAlive(scratch)) {
                castFireball(scratch[0], scratch[1], scratch[2]);
            } else {
                // Nobody in sight: scorch the floor ahead of the party.
                party.center(false, center);
                if (level.snap(
                        center[0] + (rng.nextFloat() - 0.5f) * 12,
                        center[1],
                        center[2] + (rng.nextFloat() - 0.5f) * 12,
                        2f,
                        scratch)) {
                    castFireball(scratch[0], scratch[1], scratch[2]);
                }
            }
        }
    }

    @Override
    public void extract(AppContext ctx, int index) {
        RenderPacket p = packets.packet(index);
        p.reset();
        p.frame = ctx.frame();
        p.time = (float) time;
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
        // A crypt: faint cold moonlight through cracks, torches do the rest.
        p.sunDirection[0] = -0.35f;
        p.sunDirection[1] = -0.85f;
        p.sunDirection[2] = -0.4f;
        p.sunColor[0] = 0.22f;
        p.sunColor[1] = 0.27f;
        p.sunColor[2] = 0.4f;
        p.environmentDiffuse = 0.12f;
        p.environmentSpecular = 0.15f;
        p.exposure = 1.1f;
        int lights = 0;
        if (levelBuilt) {
            probe.mark(AllocProbe.START);
            world.extract(p);
            probe.mark(AllocProbe.WORLD);
            party.center(false, center);
            level.extractLights(p, (float) time, center[0], center[2], 3);
            p.addPointLight(center[0], center[1] + 2.6f, center[2], 12, 1f, 0.75f, 0.5f, 14);
            int shadowed = p.lightCount - 1;
            p.setLightShadow(shadowed, 1);
            lights = p.lightCount;
            if (options.lightCount() > lights) {
                lights += enemies.extractLights(p, options.lightCount() - lights);
            }
            probe.mark(AllocProbe.LIGHTS);
            spells.extract(p);
            party.extract(p);
            if (markerAge < 0.8f) {
                float t = markerAge / 0.8f;
                p.addSelectionCircle(
                        markerX, markerY, markerZ, 0.3f + 0.5f * t, 0.4f, 1f, 0.5f, 1 - t);
            }
            int reveals = 0;
            for (int i = 0; i < Party.SIZE && reveals < 4; i++) {
                if (party.selected[i] || party.selectionCount == 0) {
                    int a = party.agent[i];
                    p.addReveal(level.crowd.x(a), level.crowd.y(a) + 1, level.crowd.z(a), 2.5f);
                    reveals++;
                }
            }
            probe.mark(AllocProbe.EFFECTS);
            level.fog.extract(p);
            if (turnBasedOn && boundaryCount > 0) {
                level.nav.debugDrawPolygons(
                        debugSink, debugThread, boundary, boundaryCount, 0.08f, 0xFF40E0FF);
            }
            renderer.debugDraw().collect(p);
            probe.mark(AllocProbe.FOG);
        }
        if (ready) {
            framesSinceReady++;
        }
        fillHud(ctx, index, p.count(), lights);
        probe.mark(AllocProbe.HUD);
        long now = System.nanoTime();
        lastGameNanos = frameStart == 0 ? 0 : now - frameStart;
        frameStart = 0;
        if (measuring) {
            perf.recordGame(lastGameNanos, p.count(), lights);
        }
        hud.gameNanos[index] = lastGameNanos;
    }

    private void fillHud(AppContext ctx, int index, int instances, int lights) {
        DemoHud h = hud;
        Input in = ctx.input();
        CrpgGestures g = ctx.gestures();
        h.loading[index] = !ready;
        int total = 0;
        int done = 0;
        if (!ready && world != null) {
            total = 5 + partyModels.length + enemyModels.length;
            done = (scenes.isReady(levelScene) ? 1 : 0) + (levelBuilt ? 4 + total - 5 : 0);
            if (levelBuilt) {
                total += world.renderableCount();
                done += world.renderableCount() - world.loadingRenderables();
            }
        }
        h.loadProgress[index] = total == 0 ? 1 : (float) done / total;
        h.paused[index] = ctx.simulation().isPaused();
        h.profiler[index] = profiler;
        h.instances[index] = instances;
        h.lights[index] = lights;
        h.perf[index] = options.perf();
        h.measuring[index] = measuring;
        h.perfRemaining[index] =
                (float)
                        (measuring
                                ? options.perfSeconds - (time - measureStart)
                                : options.warmupSeconds - (loadedTime < 0 ? 0 : time - loadedTime));
        h.screenshot[index] = screenshotRequested;
        screenshotRequested = false;
        h.pixelScale[index] = in.windowWidth() > 0 ? (float) in.pixelWidth() / in.windowWidth() : 1;
        if (levelBuilt) {
            h.targeting[index] = spells.targeting;
            h.turnBased[index] = turnBasedOn;
            h.moveCost[index] = moveCost;
            h.moveBudget[index] = TURN_MOVEMENT;
            h.selected[index] = party.selectionCount;
            h.enemiesAlive[index] = enemies.alive;
            h.enemiesVisible[index] = enemies.visibleCount;
            h.formation[index] = party.formation;
            h.box[index] = g.isBoxSelecting() && !spells.targeting;
            h.boxRect[index * 4] = g.boxMinX();
            h.boxRect[index * 4 + 1] = g.boxMinY();
            h.boxRect[index * 4 + 2] = g.boxMaxX();
            h.boxRect[index * 4 + 3] = g.boxMaxY();
        }
    }

    @Override
    public void shutdown(AppContext ctx) {
        if (measuring) {
            perf.end();
            measuring = false;
        }
        LOG.info("instances " + instanceCount + ", ambience " + ambienceStarted);
    }
}
