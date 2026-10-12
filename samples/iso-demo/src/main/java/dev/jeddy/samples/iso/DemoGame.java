package dev.jeddy.samples.iso;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Skeleton;
import dev.jeddy.app.AppContext;
import dev.jeddy.app.Game;
import dev.jeddy.app.audio.AudioSync;
import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.LevelLoader;
import dev.jeddy.app.world.World;
import dev.jeddy.app.world.WorldAssets;
import dev.jeddy.asset.AssetManager;
import dev.jeddy.asset.AssetState;
import dev.jeddy.asset.AssetType;
import dev.jeddy.asset.format.LevelData;
import dev.jeddy.asset.format.RulesData;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.audio.Audio;
import dev.jeddy.audio.SoundClip;
import dev.jeddy.core.log.Log;
import dev.jeddy.core.log.Logger;
import dev.jeddy.editor.Editor;
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
import dev.jeddy.rules.ActionBudget;
import dev.jeddy.rules.Content;

/**
 * The iso demo's game thread (TASKS M10-03 to M10-05, M12-07): loads the crypt, the characters and
 * their animation libraries, then runs the party, the skeletons, their animations, fireballs, fog
 * of war, the turn-based movement overlay and the camera, and extracts each frame into a render
 * packet. With {@code --perf} a script plays it (formation tours, wandering skeletons, a fireball
 * every 1.5 s, an orbiting camera) while {@link PerfCapture} measures.
 *
 * <p>Thread ownership: the game thread ({@link Game} callbacks). Allocates nothing per frame once
 * loaded.
 */
final class DemoGame implements Game {
    private static final Logger LOG = Log.logger("iso-demo");

    /** Joints per character rig (KayKit: 41), for sizing the animation pools. */
    private static final int JOINTS = 48;

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
    private DemoUi demoUi;
    private long uiNanos;
    private int lastInstances;
    private int lastLights;
    private boolean menuPausedSimulation;

    private AssetType<SceneData> scenes;
    private AssetType<Bvh> collisionAssets;
    private AssetType<NavMeshData> navAssets;
    private AssetType<OcclusionGrid> occlusionAssets;
    private AssetType<LevelData> levels;
    private AssetType<RulesData> rulesAssets;
    private int rulesAsset;
    private int rulesVersion = -1;
    private LevelLoader levelLoader;
    private AssetType<SoundClip> sounds;
    private int levelAsset;
    private int levelCollision;
    private int levelNav;
    private int levelOcclusion;
    private final int[] partyModels = new int[Characters.PARTY.length];
    private final int[] enemyModels = new int[Characters.ENEMIES.length];
    private final int[] animationLibraries = new int[Animator.LIBRARIES.length];
    private int boomSound;
    private int ambienceSound;
    private boolean ambienceStarted;

    private World world;
    private Level level;
    private Party party;
    private Enemies enemies;
    private Animator animator;
    private long animationNanos;
    private Spells spells;
    private Combat combat;
    private AgentSync sync;
    private Audio audio;
    private AudioSync audioSync;
    private TurnBasedNav turnBased;
    private DebugSink debugSink;
    private int debugThread;

    private final IsoCamera camera = new IsoCamera();

    /** The level editor (M14): F10 shows it over the running game; it edits the crypt live. */
    private final Editor editor = new Editor();

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
    private float floodBudget;
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
        levels = assets.register("level", WorldAssets.levels());
        rulesAssets = assets.register("rules", WorldAssets.rules());
        demoUi = new DemoUi(ctx, assets, renderer.textures());
        demoUi.vsync = options.vsync;
        demoUi.profiler = profiler;
        demoUi.inventoryOpen = options.showUi;
        demoUi.menuOpen = options.showUi;
        levelAsset = levels.load(Level.LEVEL);
        levelCollision = collisionAssets.load(Level.COLLISION);
        levelNav = navAssets.load(Level.NAVMESH);
        levelOcclusion = occlusionAssets.load(Level.OCCLUSION);
        rulesAsset = rulesAssets.load(Level.RULES);
        for (int i = 0; i < partyModels.length; i++) {
            partyModels[i] = scenes.load(Characters.path(Characters.PARTY[i]));
        }
        for (int i = 0; i < enemyModels.length; i++) {
            enemyModels[i] = scenes.load(Characters.path(Characters.ENEMIES[i]));
        }
        for (int i = 0; i < animationLibraries.length; i++) {
            animationLibraries[i] = scenes.load(Animator.LIBRARIES[i]);
        }
        audio = renderer.audio();
        if (audio != null) {
            sounds = assets.register("sound", audio.loader());
            boomSound = sounds.load("audio/fireball.wav");
            ambienceSound = sounds.load("audio/crypt_ambience.wav");
        }
        int characters = Party.SIZE + options.enemyCount();
        // One animated instance per character (KayKit rigs have 41 joints).
        Animations animations =
                new Animations(characters, characters * JOINTS, ctx.jobs().threadSlots());
        world =
                new World(
                        8192 + characters * 16 + options.instanceCount(),
                        renderer.meshes(),
                        animations);
        levelLoader = new LevelLoader(world, scenes);
        // Size the packets for the peak up front, so nothing grows mid-game (DESIGN §4.2).
        for (int i = 0; i < DemoHud.FRAMES; i++) {
            packets.packet(i)
                    .reserve(
                            4096 + characters * 12 + options.instanceCount(),
                            options.lightCount() + 64 + Spells.MAX,
                            64,
                            8 + Spells.MAX * 3,
                            16_384,
                            characters * JOINTS);
        }
        debugSink = renderer.debugDraw()::line;
        debugThread = ctx.jobs().threadIndex();
        camera.distance = options.perf() ? 30 : 22;
        camera.yaw = MathF.PI * 0.25f;
        camera.pitch = MathF.toRadians(52);
        controller.minDistance = 8;
        controller.maxDistance = 48;
        controller.smoothing = 0.15f;
        controller.syncGoals(); // keep the distance and yaw set above

        perf.agents = characters;
        editor.loadSettings(ctx.settings());
    }

    // Loading ------------------------------------------------------------------------------------

    private boolean allReady() {
        // The level's prefab scenes load through the loader once the level itself is in, and
        // the loader instantiates the placements as they arrive.
        if (ready(levels, levelAsset) && levelLoader.level() == null) {
            levelLoader.load(levels.get(levelAsset));
        }
        if (levelLoader.level() != null) {
            levelLoader.update();
        }
        boolean ok =
                levelLoader.isReady()
                        && ready(collisionAssets, levelCollision)
                        && ready(navAssets, levelNav)
                        && ready(occlusionAssets, levelOcclusion)
                        && ready(rulesAssets, rulesAsset);
        for (int h : partyModels) {
            ok &= ready(scenes, h);
        }
        for (int h : enemyModels) {
            ok &= ready(scenes, h);
        }
        for (int h : animationLibraries) {
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
                        ctx.jobs(),
                        levelLoader.level(),
                        levelLoader,
                        collisionAssets.get(levelCollision),
                        navAssets.get(levelNav),
                        occlusionAssets.get(levelOcclusion),
                        characters,
                        options.seed);
        sync = new AgentSync(level.crowd, world);
        pickQuery = new QueryContext(characters);
        editor.attach(world, levelLoader);
        editor.attachAssets(renderer.vfs(), scenes);
        editor.open(levelLoader.level(), options.levelSource);
        if (options.editor) {
            toggleEditor();
            // The game camera has not followed the party yet on this first frame: look at the
            // level (or whatever --editor-command selects).
            for (String command : options.editorCommands) {
                editor.runCommand(command);
            }
            editor.viewport().frameSelection();
        }
        SceneData[] partyScenes = new SceneData[partyModels.length];
        for (int i = 0; i < partyScenes.length; i++) {
            partyScenes[i] = scenes.get(partyModels[i]);
        }
        SceneData[] enemyScenes = new SceneData[enemyModels.length];
        for (int i = 0; i < enemyScenes.length; i++) {
            enemyScenes[i] = scenes.get(enemyModels[i]);
        }
        SceneData[] libraries = new SceneData[animationLibraries.length];
        for (int i = 0; i < libraries.length; i++) {
            libraries[i] = scenes.get(animationLibraries[i]);
        }
        animator =
                new Animator(
                        world,
                        level.crowd,
                        libraries,
                        new Skeleton[] {
                            Skeleton.of(partyScenes[0], 0), Skeleton.of(enemyScenes[0], 0)
                        },
                        Characters.SCALE,
                        characters);
        party = new Party(level, world, sync, partyScenes, animator, rng);
        party.center(false, center);
        enemies =
                new Enemies(
                        level,
                        world,
                        sync,
                        enemyScenes,
                        animator,
                        options.enemyCount(),
                        rng,
                        options.perf(),
                        options.perf() ? 3 : 45,
                        center[0],
                        center[2]);
        spells = new Spells(audio, characters);
        buildCombat();
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
                        + level.data.lightCount()
                        + " lights");
    }

    /**
     * Whether scene node {@code i} is {@code root} or below it (any node when {@code root} is -1).
     */
    private static boolean underNode(SceneData s, int i, int root) {
        while (i >= 0) {
            if (root < 0 || i == root) {
                return true;
            }
            i = s.parent(i);
        }
        return false;
    }

    /**
     * Scatters {@code n} static props (crates, barrels, rubble from the crypt's own prefabs) over
     * the floor, for the perf scene's instance count.
     */
    private void scatterProps(int n) {
        if (n <= 0) {
            return;
        }
        // The meshes of the prefabs the level places as props and decor.
        LevelData data = level.data;
        long[] props = new long[64];
        int count = 0;
        for (int p = 0; p < data.prefabCount(); p++) {
            boolean prop = false;
            for (int i = 0; i < data.placementCount() && !prop; i++) {
                String layer = data.placementLayer(i);
                prop =
                        data.placementPrefab(i) == p
                                && (layer.equals("prop") || layer.equals("decor"));
            }
            SceneData s = levelLoader.prefabScene(p);
            if (!prop || s == null) {
                continue;
            }
            int root = levelLoader.prefabRootNode(p);
            for (int i = 0; i < s.nodeCount(); i++) {
                int m = s.mesh(i);
                if (m < 0 || !underNode(s, i, root)) {
                    continue;
                }
                long id = s.meshId(m);
                boolean seen = false;
                for (int k = 0; k < count; k++) {
                    seen |= props[k] == id;
                }
                if (!seen && count < props.length) {
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
        party.fight(dt, enemies);
        combat.tick(dt);
        party.updateDown();
        if (options.autoTurns) {
            autoTurn(dt);
        }
        if (options.echoCombat) {
            echoCombatLog();
        }
        a.mark(AllocProbe.PARTY);
        level.crowd.update(dt);
        a.mark(AllocProbe.CROWD);
        sync.apply(dt);
        a.mark(AllocProbe.SYNC);
        level.collision.update();
        a.mark(AllocProbe.COLLISION);
        spells.tick(dt, level.collision, combat);
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
        demoUi.beginFrame(dt);
        demoUi.log.setTime((float) time);
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
            if (rulesAssets.version(rulesAsset) != rulesVersion) {
                buildCombat(); // the content was re-cooked (the editor saved it): fresh creatures
            }
            if (in.wasKeyPressed(Scancode.F10) && !demoUi.ui.wantsKeyboard()) {
                toggleEditor();
            }
            if (editor.isShown()) {
                // The editor owns the pointer, the keys and the camera while it is open; the
                // game keeps simulating underneath (Space pauses it as usual).
                editor.update(ctx, demoUi.ui, dt);
                if (in.wasKeyPressed(Scancode.SPACE) && !demoUi.ui.wantsKeyboard()) {
                    ctx.simulation().setPaused(!ctx.simulation().isPaused());
                }
            } else {
                handleInput(ctx, in);
            }
        }
        probe.mark(AllocProbe.INPUT);
        if (levelBuilt) {
            // Poses advance per frame, not per tick: they need no interpolation.
            long t0 = System.nanoTime();
            animator.update(dt);
            world.updateAnimations(dt, ctx.jobs());
            animationNanos = System.nanoTime() - t0;
        }
        probe.mark(AllocProbe.ANIMATION);
        if (!editor.isShown()) {
            updateCamera(ctx, in, dt);
        }
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
        if (in.wasKeyPressed(Scancode.ESCAPE) && !demoUi.ui.wantsKeyboard() && !editor.isShown()) {
            if (spells != null && spells.targeting) {
                spells.targeting = false;
            } else if (demoUi.inventoryOpen) {
                demoUi.inventoryOpen = false;
            } else {
                demoUi.menuOpen = !demoUi.menuOpen;
            }
        }
        if (demoUi.quitRequested) {
            ctx.quit();
        }
        timers(ctx);
        probe.mark(AllocProbe.TIMERS);
        fillUi(ctx);
        demoUi.editorOpen = editor.isShown();
        demoUi.declare(party);
        editor.declare(demoUi.ui, demoUi.w);
        probe.mark(AllocProbe.UI);
    }

    /** Opens the editor over the current view, or closes it (F10). */
    private void toggleEditor() {
        if (editor.isShown()) {
            editor.setShown(false);
            demoUi.log.start().add("Editor closed").end();
        } else {
            editor.takeCameraFrom(camera);
            editor.setShown(true);
            demoUi.log
                    .start()
                    .add(
                            "Editor: click selects, W/E/R move/rotate/scale, right drag orbits, F10"
                                    + " closes")
                    .end();
        }
    }

    /** Hands the frame's state to the UI and applies what the player changed in it. */
    private void fillUi(AppContext ctx) {
        DemoUi u = demoUi;
        Input in = ctx.input();
        CrpgGestures g = ctx.gestures();
        u.loading = !ready;
        int total = 0;
        int done = 0;
        if (!ready && world != null) {
            total = 5 + partyModels.length + enemyModels.length;
            done = (levels.isReady(levelAsset) ? 1 : 0) + (levelBuilt ? 4 + total - 5 : 0);
            if (levelBuilt) {
                total += world.renderableCount();
                done += world.renderableCount() - world.loadingRenderables();
            }
        }
        u.loadProgress = total == 0 ? 1 : (float) done / total;
        // The menu pauses the simulation; closing it resumes unless Space paused it before.
        if (u.menuOpen && !menuPausedSimulation && !ctx.simulation().isPaused()) {
            ctx.simulation().setPaused(true);
            menuPausedSimulation = true;
        } else if (!u.menuOpen && menuPausedSimulation) {
            ctx.simulation().setPaused(false);
            menuPausedSimulation = false;
        }
        u.simulationPaused = ctx.simulation().isPaused();
        u.perf = options.perf();
        u.measuring = measuring;
        u.perfRemaining =
                (float)
                        (measuring
                                ? options.perfSeconds - (time - measureStart)
                                : options.warmupSeconds - (loadedTime < 0 ? 0 : time - loadedTime));
        u.gameMs = lastGameNanos * 1e-6;
        u.mainMs = renderer.mainMs();
        u.instances = lastInstances;
        u.lights = lastLights;
        u.profiler = profiler;
        if (levelBuilt) {
            u.targeting = spells.targeting;
            u.turnBased = turnBasedOn;
            u.moveCost = moveCost;
            u.moveBudget = moveBudget();
            if (u.endTurnClicked) {
                combat.endTurn();
            }
            u.selectedCount = party.selectionCount;
            u.enemiesAlive = enemies.alive;
            u.enemiesVisible = enemies.visibleCount;
            u.formation = party.formation;
            u.boxSelecting = g.isBoxSelecting() && !spells.targeting && !u.ui.wantsMouse();
            float k = in.uiScale(); // the gestures are in window coordinates, the UI in logical
            u.boxX0 = g.boxMinX() * k;
            u.boxY0 = g.boxMinY() * k;
            u.boxX1 = g.boxMaxX() * k;
            u.boxY1 = g.boxMaxY() * k;
            if (u.clickedMember >= 0) {
                party.select(u.clickedMember, u.shift);
            }
            if (u.formationChanged) {
                party.setFormation(u.formation);
                demoUi.log.start().add("Formation: ").add(Party.FORMATION_NAMES[u.formation]).end();
            }
            if (u.turnBasedChanged) {
                setTurnBased(u.turnBased);
            }
        }
        if (u.profilerChanged) {
            profiler = u.profiler;
        }
        if (u.volumeChanged && audio != null) {
            audio.setMasterGain(u.masterVolume);
            audio.setBusGain(Audio.BUS_AMBIENT, u.ambience ? 1 : 0);
        }
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
        // The UI consumed the pointer (a panel is under it) or the keyboard (a field has focus).
        boolean uiMouse = demoUi.ui.wantsMouse();
        boolean uiKeys = demoUi.ui.wantsKeyboard() || demoUi.menuOpen;

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
        party.hovered = unit >= 0 && !uiMouse ? party.memberOf(unit) : -1;
        enemies.hovered = unit >= 0 && !uiMouse ? enemies.enemyOf(unit) : -1;

        // Keys.
        if (!uiKeys) {
            keys(ctx, in, shift);
        }

        // Mouse.
        spells.aimValid = (groundValid || enemies.hovered >= 0) && !uiMouse;
        spells.aimX = groundX;
        spells.aimY = groundY;
        spells.aimZ = groundZ;
        if (uiMouse) {
            // Nothing in the world reacts while the pointer is on a panel.
        } else if (spells.targeting) {
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
        } else if (turnBasedOn && combat.active()) {
            turnOrders(g);
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

    private void keys(AppContext ctx, Input in, boolean shift) {
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
            demoUi.log.start().add("Formation: ").add(Party.FORMATION_NAMES[party.formation]).end();
        }
        if (in.wasKeyPressed(Scancode.F)) {
            spells.targeting = !spells.targeting;
        }
        if (in.wasKeyPressed(Scancode.T)) {
            setTurnBased(!turnBasedOn);
        }
        if (in.wasKeyPressed(Scancode.RETURN) || in.wasKeyPressed(Scancode.KP_ENTER)) {
            combat.endTurn();
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
        if (in.wasKeyPressed(Scancode.I)) {
            demoUi.inventoryOpen = !demoUi.inventoryOpen;
        }
    }

    private void order(float x, float y, float z) {
        party.order(x, y, z);
        markerX = x;
        markerY = y;
        markerZ = z;
        markerAge = 0;
    }

    private int echoedVersion;
    private float autoTurnTimer;

    private float marchTimer = 7;

    /**
     * Scripted turn-based play ({@code --auto-turns}): the party marches on the nearest skeleton
     * every few seconds while no fight is on; in a fight a member strikes whoever is in reach, then
     * ends its turn.
     */
    private void autoTurn(float dt) {
        marchTimer += dt;
        if (!combat.active() && marchTimer > 8) {
            marchTimer = 0; // the perf tour's waypoints pass every post
            float x = TOUR[tourIndex * 2];
            float z = TOUR[tourIndex * 2 + 1];
            tourIndex = (tourIndex + 1) % (TOUR.length / 2);
            if (level.snap(x, 0, z, 3f, scratch)) {
                party.orderAll(scratch[0], scratch[1], scratch[2]);
            }
            LOG.info(
                    "auto-turns: party at "
                            + level.crowd.x(party.agent[0])
                            + ", "
                            + level.crowd.z(party.agent[0])
                            + " -> "
                            + x
                            + ", "
                            + z);
        }
        if (!turnBasedOn || !combat.active()) {
            return;
        }
        int c = combat.current();
        int member = combat.memberOf(c);
        if (member < 0) {
            autoTurnTimer = 0;
            return;
        }
        autoTurnTimer += dt;
        if (autoTurnTimer < 1) {
            return;
        }
        autoTurnTimer = 0;
        int a = party.agent[member];
        int target = enemies.nearestAlive(level.crowd.x(a), level.crowd.z(a), Enemies.REACH + 0.4f);
        if (target >= 0) {
            party.strikeAt(member, enemies, enemies.enemyOf(target));
        }
        combat.endTurn();
    }

    /** Prints new combat-log lines to the console log ({@code --echo-combat}; allocates). */
    private void echoCombatLog() {
        CombatLog log = demoUi.log;
        if (log.version() == echoedVersion) {
            return;
        }
        int added = Math.min(log.count(), log.version() - echoedVersion);
        for (int i = log.count() - added; i < log.count(); i++) {
            LOG.info("combat: " + new String(log.chars(i), 0, log.length(i)));
        }
        echoedVersion = log.version();
    }

    /** Builds (or rebuilds, after a content reload) the rules side of the fight. */
    private void buildCombat() {
        rulesVersion = rulesAssets.version(rulesAsset);
        boolean turnBasedBefore = combat != null && combat.turnBasedMode;
        combat =
                new Combat(
                        new Content(rulesAssets.get(rulesAsset)),
                        options.seed,
                        party,
                        enemies,
                        animator,
                        level.crowd,
                        demoUi.log);
        combat.unlimitedSlots = options.perf();
        combat.invulnerableParty = options.perf();
        combat.turnBasedMode = turnBasedBefore;
        party.combat = combat;
        enemies.combat = combat;
        demoUi.combat = combat;
        if (!levelBuilt && options.turnBased) {
            setTurnBased(true);
        }
        if (levelBuilt) {
            demoUi.log.start().add("Rules reloaded: everyone is rebuilt from the content").end();
        }
    }

    private void setTurnBased(boolean on) {
        turnBasedOn = on;
        floodMember = -1;
        combat.setTurnBased(on);
        demoUi.log.start().add(on ? "Turn-based mode (Enter ends a turn)" : "Real time").end();
    }

    /** The meters the acting creature may still walk, for the range display. */
    private float moveBudget() {
        if (turnBasedOn && combat.active()) {
            int c = combat.current();
            return c >= 0 ? combat.movementBudget(c) : 0;
        }
        return TURN_MOVEMENT;
    }

    /** The player's orders during a party member's turn: strike a skeleton in reach or walk. */
    private void turnOrders(CrpgGestures g) {
        int c = combat.current();
        int member = combat.memberOf(c);
        if (member < 0) {
            return; // a skeleton's turn
        }
        if (g.selectClicked() && party.hovered >= 0) {
            party.select(party.hovered, false); // looking, not acting
            return;
        }
        boolean clicked = g.selectClicked() || g.commandClicked();
        if (!clicked) {
            return;
        }
        if (enemies.hovered >= 0) {
            if (!party.strikeAt(member, enemies, enemies.hovered)) {
                demoUi.log.start().add("Out of reach or no attack left").end();
            }
        } else if (groundValid) {
            if (moveCost < 0 || !combat.move(c, groundX, groundY, groundZ, moveCost)) {
                demoUi.log.start().add("Too far to walk this turn").end();
            } else {
                markerX = groundX;
                markerY = groundY;
                markerZ = groundZ;
                markerAge = 0;
            }
        }
    }

    /** The party's caster (the first mage) throws a fireball at {@code (x, y, z)}. */
    private void castFireball(float x, float y, float z) {
        int caster = combat.partyCreature(2);
        if (!combat.canAct(caster)) {
            demoUi.log.start().add(Characters.PARTY_NAMES[2]).add(" is down").end();
            return;
        }
        if (!combat.hasFireball(caster)) {
            demoUi.log.start().add(Characters.PARTY_NAMES[2]).add(" has no fireball left").end();
            return;
        }
        if (turnBasedOn
                && combat.active()
                && (combat.current() != caster
                        || !combat.encounter.hasAction(caster, ActionBudget.STANDARD))) {
            demoUi.log.start().add("Not ").add(Characters.PARTY_NAMES[2]).add("'s action").end();
            return;
        }
        int a = party.agent[2];
        party.cast(2, x, z);
        spells.cast(caster, level.crowd.x(a), level.crowd.y(a), level.crowd.z(a), x, y, z);
    }

    /** Floods the acting member's movement range when it moved, and prices the hovered point. */
    private void updateTurnBased() {
        int m = combat.active() ? combat.memberOf(combat.current()) : party.leader();
        if (m < 0) {
            boundaryCount = 0;
            moveCost = -1;
            return;
        }
        int a = party.agent[m];
        float x = level.crowd.x(a);
        float z = level.crowd.z(a);
        float budget = moveBudget();
        if (m != floodMember
                || Math.abs(x - floodX) + Math.abs(z - floodZ) > 0.05f
                || budget != floodBudget) {
            floodMember = m;
            floodX = x;
            floodZ = z;
            floodBudget = budget;
            int ref =
                    level.query.findNearestPoly(
                            x, level.crowd.y(a), z, 0.5f, 2f, 0.5f, level.filter, scratch);
            boundaryCount = 0;
            if (ref != NavMesh.NULL_REF) {
                turnBased.flood(ref, scratch[0], scratch[1], scratch[2], budget, level.filter);
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
        IsoCamera cam = editor.isShown() ? editor.camera() : camera;
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
        p.far = cam.distance * 2 + 60;
        p.orthographic = cam.orthographic;
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
            if (editor.isShown()) {
                p.fogOfWar = null; // the whole level is visible while editing it
            } else {
                level.fog.extract(p);
            }
            if (turnBasedOn && boundaryCount > 0) {
                level.nav.debugDrawPolygons(
                        debugSink, debugThread, boundary, boundaryCount, 0.08f, 0xFF40E0FF);
            }
            renderer.debugDraw().collect(p);
            editor.extract(p); // grid, markers and the gizmo (an overlay) after the game's lines
            probe.mark(AllocProbe.FOG);
        }
        if (ready) {
            framesSinceReady++;
        }
        fillHud(index);
        probe.mark(AllocProbe.HUD);
        demoUi.extract(p);
        uiNanos = demoUi.buildNanos() + demoUi.layoutNanos();
        probe.mark(AllocProbe.UI);
        lastInstances = p.count();
        lastLights = lights;
        long now = System.nanoTime();
        lastGameNanos = frameStart == 0 ? 0 : now - frameStart;
        frameStart = 0;
        if (measuring) {
            perf.recordGame(lastGameNanos, p.count(), lights, animationNanos, uiNanos);
        }
        hud.gameNanos[index] = lastGameNanos;
    }

    private void fillHud(int index) {
        DemoHud h = hud;
        h.profiler[index] = profiler;
        h.measuring[index] = measuring;
        h.screenshot[index] = screenshotRequested;
        screenshotRequested = false;
    }

    @Override
    public void shutdown(AppContext ctx) {
        if (measuring) {
            perf.end();
            measuring = false;
        }
        editor.saveSettings(ctx.settings());
        try {
            ctx.saveSettings();
        } catch (java.io.IOException e) {
            LOG.warn("settings not saved", e);
        }
        LOG.info("instances " + instanceCount + ", ambience " + ambienceStarted);
    }
}
