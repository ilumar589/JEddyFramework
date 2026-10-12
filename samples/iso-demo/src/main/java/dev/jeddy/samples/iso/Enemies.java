package dev.jeddy.samples.iso;

import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.render.RenderPacket;

/**
 * The skeletons (TASKS M10-04): wait at their posts or wander, chase the nearest party member they
 * can see, crowd around it and strike at it, give up when it gets away, flinch when hurt, die (the
 * body stays a moment) and (optionally) rise again elsewhere. Hidden while their position is
 * outside the party's sight. Animated through the {@link Animator} (TASKS M12-07). Since M15 the
 * strikes, the hit points and the deaths are the rules' ({@link Combat}). Since M22 (DESIGN §22) a
 * skeleton is an entity with {@link Monster}, {@link Mind}, {@link Sight} and {@link Flash}
 * components in the generated {@code CryptGame} stores, and its logic is {@link SkeletonAi}; this
 * class spawns them, keeps the few whole-population numbers (alive, visible, kills, the hovered
 * one) and gives the rest of the demo the lookups it had. Skeleton {@code i} is row {@code i} of
 * every store: rows are added in order and never removed (a dead skeleton keeps its row and rises
 * again), which {@link #attach} checks.
 *
 * <p>Thread ownership: the game thread. Allocates nothing after construction.
 */
final class Enemies {
    static final float AGGRO = 12;
    static final float LEASH = 26;
    static final float REACH = 1.5f;

    /** Seconds a body stays visible after death. */
    static final float CORPSE_SECONDS = 2.5f;

    final int count;
    int alive;

    /** The rules; set once the party and the skeletons exist. */
    Combat combat;

    /** Skeletons slain so far. */
    int kills;

    int hovered = -1;

    /** Whether idle skeletons wander (the perf scene). */
    final boolean wander;

    /** Seconds from death to rising again; 0 for never. */
    final float respawnSeconds;

    private final Level level;
    private final World world;
    private final AgentSync sync;
    private final Crowd crowd;
    private final Rng rng;
    private final AgentParams params = new AgentParams();
    private final Animator animator;
    private final int[] enemyOfAgent;
    private final int[] root;
    private final int[] model0;
    private final int[] agent0;
    private final int[] character0;
    private final float[] home0;
    private CryptGame game;

    Enemies(
            Level level,
            World world,
            AgentSync sync,
            SceneData[] models,
            Animator animator,
            int count,
            Rng rng,
            boolean wander,
            float respawnSeconds,
            float partyX,
            float partyZ) {
        this.level = level;
        this.world = world;
        this.sync = sync;
        this.crowd = level.crowd;
        this.count = count;
        this.rng = rng;
        this.wander = wander;
        this.respawnSeconds = respawnSeconds;
        this.animator = animator;
        root = new int[count];
        model0 = new int[count];
        agent0 = new int[count];
        character0 = new int[count];
        home0 = new float[count * 3];
        enemyOfAgent = new int[crowd.capacity()];
        java.util.Arrays.fill(enemyOfAgent, -1);
        params.radius = 0.4f;
        params.maxSpeed = 2.6f;
        params.maxAcceleration = 10f;
        params.separationWeight = 1.2f;

        float[] p = new float[3];
        int spawns = level.enemySpawns.length / 3;
        boolean scatter = count > spawns * 12;
        for (int i = 0; i < count; i++) {
            if (scatter || !ring(i, spawns, p)) {
                randomPoint(partyX, partyZ, 14, p);
            }
            home0[i * 3] = p[0];
            home0[i * 3 + 1] = p[1];
            home0[i * 3 + 2] = p[2];
            model0[i] = i % models.length;
            float yaw = rng.nextFloat() * (float) (Math.PI * 2);
            root[i] = Characters.spawn(world, models[model0[i]], p[0], p[1], p[2], yaw);
            agent0[i] = crowd.addAgent(p[0], p[1], p[2], params);
            enemyOfAgent[agent0[i]] = i;
            sync.bind(agent0[i], root[i], yaw);
            character0[i] = animator.add(root[i], Animator.SKELETONS, agent0[i], rng);
        }
        alive = count;
    }

    /**
     * Gives every skeleton its components in the registry's stores (row {@code i} for skeleton
     * {@code i}); the registry needs this object first, hence the two steps.
     */
    void attach(CryptGame game) {
        this.game = game;
        MonsterStore monsters = game.monster();
        MindStore minds = game.mind();
        SightStore sights = game.sight();
        FlashStore flashes = game.flash();
        for (int i = 0; i < count; i++) {
            int e = root[i];
            int row = monsters.add(e);
            if (row != i || minds.add(e) != i || sights.add(e) != i || flashes.add(e) != i) {
                throw new IllegalStateException("skeleton " + i + " is not row " + i);
            }
            monsters.model()[i] = model0[i];
            monsters.agent()[i] = agent0[i];
            monsters.character()[i] = character0[i];
            monsters.index()[i] = i;
            minds.timer()[i] = rng.nextFloat() * 4;
            minds.homeX()[i] = home0[i * 3];
            minds.homeY()[i] = home0[i * 3 + 1];
            minds.homeZ()[i] = home0[i * 3 + 2];
        }
    }

    /** Places enemy {@code i} on a ring around its spawn point; false if that is not walkable. */
    private boolean ring(int i, int spawns, float[] out) {
        int s = i % spawns;
        int k = i / spawns;
        float angle = k * 2.39996f; // golden angle
        float r = k == 0 ? 0 : 1.3f * (float) Math.sqrt(k);
        return level.snap(
                level.enemySpawns[s * 3] + (float) Math.cos(angle) * r,
                level.enemySpawns[s * 3 + 1],
                level.enemySpawns[s * 3 + 2] + (float) Math.sin(angle) * r,
                0.5f,
                out);
    }

    /** A random walkable point at least {@code clearance} meters from {@code (x, z)}. */
    private void randomPoint(float x, float z, float clearance, float[] out) {
        float[] w = level.walkable;
        int o = 0;
        for (int attempt = 0; attempt < 32; attempt++) {
            o = rng.nextInt(level.walkableCount) * 3;
            float dx = w[o] - x;
            float dz = w[o + 2] - z;
            if (dx * dx + dz * dz >= clearance * clearance) {
                break;
            }
        }
        out[0] = w[o];
        out[1] = w[o + 1];
        out[2] = w[o + 2];
    }

    // ---- Lookups over the stores --------------------------------------------------------------

    /** The root entity of skeleton {@code i}. */
    int entity(int i) {
        return root[i];
    }

    int model(int i) {
        return game.monster().model()[i];
    }

    /** The crowd agent of skeleton {@code i}, or -1 while it is dead. */
    int agent(int i) {
        return game.monster().agent()[i];
    }

    int character(int i) {
        return game.monster().character()[i];
    }

    boolean isDead(int i) {
        return game.mind().state()[i] == MonsterState.DEAD.ordinal();
    }

    boolean visible(int i) {
        return game.sight().visible()[i];
    }

    /** How many the party sees (counted from the Sight store). */
    int visibleCount() {
        boolean[] v = game.sight().visible();
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (v[i]) {
                n++;
            }
        }
        return n;
    }

    /** Returns the enemy driving crowd agent {@code a}, or -1. */
    int enemyOf(int a) {
        return a >= 0 && a < enemyOfAgent.length ? enemyOfAgent[a] : -1;
    }

    /**
     * Returns the crowd agent of the nearest living enemy the party can see within {@code radius}
     * of {@code (x, z)}, or -1.
     */
    int nearestAlive(float x, float z, float radius) {
        int[] state = game.mind().state();
        boolean[] visible = game.sight().visible();
        int[] agent = game.monster().agent();
        int dead = MonsterState.DEAD.ordinal();
        int best = -1;
        float bestD2 = radius * radius;
        for (int i = 0; i < count; i++) {
            if (state[i] == dead || !visible[i]) {
                continue;
            }
            int a = agent[i];
            float dx = crowd.x(a) - x;
            float dz = crowd.z(a) - z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = a;
            }
        }
        return best;
    }

    // ---- Changes the rest of the demo makes ---------------------------------------------------

    /** Skeleton {@code i} was hurt: the {@link SkeletonAi#flinch} behaviour reacts. */
    void flinch(int i) {
        game.signal(Signals.HURT, root[i], 0, 0);
    }

    /** Turns skeleton {@code i} toward {@code (x, z)}. */
    void face(int i, float x, float z) {
        if (!isDead(i)) {
            sync.faceToward(agent(i), x, z);
        }
    }

    /** Hides or shows skeleton {@code i}'s model (a corpse fading, a respawn). */
    void hide(int i, boolean hidden) {
        boolean[] h = game.sight().hidden();
        if (h[i] != hidden) {
            h[i] = hidden;
            world.setGroupHidden(root[i], hidden);
        }
    }

    /** Skeleton {@code i} is destroyed (the rules said so): its body falls and lies a moment. */
    void kill(int i) {
        if (isDead(i)) {
            return;
        }
        MindStore minds = game.mind();
        MonsterStore monsters = game.monster();
        minds.state()[i] = MonsterState.DEAD.ordinal();
        minds.timer()[i] = respawnSeconds;
        int a = monsters.agent()[i];
        enemyOfAgent[a] = -1;
        sync.unbind(a);
        crowd.removeAgent(a);
        monsters.agent()[i] = -1;
        animator.setAgent(monsters.character()[i], -1);
        animator.die(monsters.character()[i]);
        minds.corpse()[i] = CORPSE_SECONDS; // hidden once the body has lain a moment
        game.sight().visible()[i] = false;
        alive--;
        kills++;
    }

    private final float[] point = new float[3];

    /** Skeleton {@code i} rises again at a random walkable point away from the party. */
    void respawn(int i, Party party) {
        float[] c = point;
        party.center(false, c);
        randomPoint(c[0], c[2], 18, c);
        int a = crowd.addAgent(c[0], c[1], c[2], params);
        MindStore minds = game.mind();
        if (a < 0) {
            minds.timer()[i] = 1;
            return;
        }
        MonsterStore monsters = game.monster();
        monsters.agent()[i] = a;
        enemyOfAgent[a] = i;
        hide(i, true); // the body vanishes; the fog shows it again
        minds.corpse()[i] = 0;
        int character = monsters.character()[i];
        animator.setAgent(character, a);
        animator.revive(character);
        world.transforms().setPosition(root[i], c[0], c[1], c[2]);
        sync.bind(a, root[i], 0);
        combat.spawnEnemy(i);
        minds.state()[i] = MonsterState.IDLE.ordinal();
        minds.timer()[i] = rng.nextFloat() * 2;
        minds.homeX()[i] = c[0];
        minds.homeY()[i] = c[1];
        minds.homeZ()[i] = c[2];
        alive++;
    }

    /**
     * Adds up to {@code budget} faint soul lights in the skeletons' eyes (the perf scene's light
     * load); returns how many. A hand-written loop over the stores' columns.
     */
    int extractLights(RenderPacket p, int budget) {
        int[] state = game.mind().state();
        int[] agent = game.monster().agent();
        int[] model = game.monster().model();
        int dead = MonsterState.DEAD.ordinal();
        int added = 0;
        for (int i = 0; i < count && added < budget; i++) {
            if (state[i] == dead) {
                continue;
            }
            int a = agent[i];
            float r = model[i] == 2 ? 0.6f : 0.25f;
            float g = model[i] == 2 ? 0.3f : 0.9f;
            float b = model[i] == 2 ? 1f : 0.6f;
            p.addPointLight(crowd.x(a), crowd.y(a) + 1.6f, crowd.z(a), 3.5f, r, g, b, 1.2f);
            added++;
        }
        return added;
    }

    /** Average position of a random living enemy's neighbourhood, for scripted targeting. */
    boolean randomAlive(float[] out) {
        if (alive == 0) {
            return false;
        }
        int[] state = game.mind().state();
        boolean[] visible = game.sight().visible();
        int[] agent = game.monster().agent();
        int dead = MonsterState.DEAD.ordinal();
        for (int attempt = 0; attempt < 16; attempt++) {
            int i = rng.nextInt(count);
            if (state[i] != dead && visible[i]) {
                out[0] = crowd.x(agent[i]);
                out[1] = crowd.y(agent[i]);
                out[2] = crowd.z(agent[i]);
                return true;
            }
        }
        return false;
    }
}
