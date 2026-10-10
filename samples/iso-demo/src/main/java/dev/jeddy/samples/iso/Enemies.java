package dev.jeddy.samples.iso;

import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.vis.Visibility;
import dev.jeddy.render.RenderPacket;

/**
 * The skeletons (TASKS M10-04): wait at their posts or wander, chase the nearest party member they
 * can see, crowd around it and strike at it, give up when it gets away, take fireball damage (and
 * flinch), die (the body stays a moment) and (optionally) rise again elsewhere. Hidden while their
 * position is outside the party's sight. Animated through the {@link Animator} (TASKS M12-07).
 *
 * <p>Thread ownership: the game thread. Allocates nothing after construction.
 */
final class Enemies {
    static final byte IDLE = 0;
    static final byte CHASE = 1;
    static final byte ATTACK = 2;
    static final byte DEAD = 3;

    static final float AGGRO = 12;
    static final float LEASH = 26;
    static final float REACH = 1.5f;
    static final float MAX_HP = 30;

    /** Seconds a body stays visible after death. */
    static final float CORPSE_SECONDS = 2.5f;

    final int count;
    final int[] agent;
    final int[] root;
    final int[] model;
    final byte[] state;
    final float[] hp;
    final boolean[] visible;
    final int[] character; // Animator index
    int alive;
    int visibleCount;
    int hovered = -1;

    private final Level level;
    private final World world;
    private final AgentSync sync;
    private final Crowd crowd;
    private final Rng rng;
    private final AgentParams params = new AgentParams();
    private final boolean wander;
    private final float respawnSeconds;
    private final float[] timer;
    private final float[] homeX;
    private final float[] homeY;
    private final float[] homeZ;
    private final float[] flash;
    private final int[] style;
    private final boolean[] hidden;
    private final float[] cooldown;
    private final float[] corpse;
    private final Animator animator;
    private int[] enemyOfAgent;

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
        agent = new int[count];
        character = new int[count];
        cooldown = new float[count];
        corpse = new float[count];
        root = new int[count];
        model = new int[count];
        state = new byte[count];
        hp = new float[count];
        visible = new boolean[count];
        timer = new float[count];
        homeX = new float[count];
        homeY = new float[count];
        homeZ = new float[count];
        flash = new float[count];
        style = new int[count];
        hidden = new boolean[count];
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
            homeX[i] = p[0];
            homeY[i] = p[1];
            homeZ[i] = p[2];
            model[i] = i % models.length;
            float yaw = rng.nextFloat() * (float) (Math.PI * 2);
            root[i] = Characters.spawn(world, models[model[i]], p[0], p[1], p[2], yaw);
            agent[i] = crowd.addAgent(p[0], p[1], p[2], params);
            enemyOfAgent[agent[i]] = i;
            sync.bind(agent[i], root[i], yaw);
            character[i] = animator.add(root[i], Animator.SKELETONS, agent[i], rng);
            hp[i] = MAX_HP;
            timer[i] = rng.nextFloat() * 4;
            hidden[i] = false;
        }
        alive = count;
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

    /** Returns the enemy driving crowd agent {@code a}, or -1. */
    int enemyOf(int a) {
        return a >= 0 && a < enemyOfAgent.length ? enemyOfAgent[a] : -1;
    }

    /**
     * Returns the crowd agent of the nearest living enemy the party can see within {@code radius}
     * of {@code (x, z)}, or -1.
     */
    int nearestAlive(float x, float z, float radius) {
        int best = -1;
        float bestD2 = radius * radius;
        for (int i = 0; i < count; i++) {
            if (state[i] == DEAD || !visible[i]) {
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

    /** One AI tick: targets, chasing, striking, giving up, bodies, respawns. */
    void think(float dt, Party party) {
        for (int i = 0; i < count; i++) {
            if (flash[i] > 0) {
                flash[i] -= dt;
            }
            cooldown[i] -= dt;
            if (state[i] == DEAD) {
                if (corpse[i] > 0) {
                    corpse[i] -= dt;
                    if (corpse[i] <= 0 && !hidden[i]) {
                        world.setGroupHidden(root[i], true);
                        hidden[i] = true;
                    }
                }
                timer[i] -= dt;
                if (respawnSeconds > 0 && timer[i] <= 0) {
                    respawn(i, party);
                }
                continue;
            }
            int a = agent[i];
            float x = crowd.x(a);
            float z = crowd.z(a);
            int best = -1;
            float bestD2 = Float.MAX_VALUE;
            for (int m = 0; m < Party.SIZE; m++) {
                int pa = party.agent[m];
                float dx = crowd.x(pa) - x;
                float dz = crowd.z(pa) - z;
                float d2 = dx * dx + dz * dz;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = pa;
                }
            }
            timer[i] -= dt;
            switch (state[i]) {
                case IDLE -> {
                    if (visible[i] && bestD2 < AGGRO * AGGRO) {
                        state[i] = CHASE;
                        timer[i] = 0;
                    } else if (wander && timer[i] <= 0) {
                        float[] w = level.walkable;
                        int o = rng.nextInt(level.walkableCount) * 3;
                        crowd.requestMoveTarget(a, w[o], w[o + 1], w[o + 2]);
                        timer[i] = 5 + rng.nextFloat() * 6;
                    }
                }
                case CHASE -> {
                    if (bestD2 > LEASH * LEASH) {
                        state[i] = IDLE;
                        timer[i] = 2 + rng.nextFloat() * 4;
                        crowd.requestMoveTarget(a, homeX[i], homeY[i], homeZ[i]);
                    } else if (bestD2 < REACH * REACH) {
                        state[i] = ATTACK;
                        crowd.resetMoveTarget(a);
                    } else if (timer[i] <= 0) {
                        crowd.requestMoveTarget(a, crowd.x(best), crowd.y(best), crowd.z(best));
                        timer[i] = 0.5f + (i % 8) * 0.05f;
                    }
                }
                case ATTACK -> {
                    if (bestD2 > (REACH + 0.8f) * (REACH + 0.8f)) {
                        state[i] = CHASE;
                        timer[i] = 0;
                    } else if (cooldown[i] <= 0 && visible[i] && animator.isFree(character[i])) {
                        sync.faceToward(a, crowd.x(best), crowd.z(best));
                        animator.action(character[i], Characters.ENEMY_ATTACK[model[i]]);
                        cooldown[i] = 1.4f + rng.nextFloat() * 1.2f;
                    }
                }
                default -> {}
            }
            animator.setCombat(character[i], state[i] != IDLE);
        }
    }

    /** Applies fireball damage to the enemy driving agent {@code a} (if any). */
    void damage(int a, float amount) {
        int i = enemyOf(a);
        if (i < 0 || state[i] == DEAD) {
            return;
        }
        hp[i] -= amount;
        flash[i] = 0.35f;
        if (state[i] == IDLE) {
            state[i] = CHASE;
            timer[i] = 0;
        }
        if (hp[i] <= 0) {
            kill(i);
        } else {
            animator.action(character[i], Animator.HIT);
        }
    }

    private void kill(int i) {
        state[i] = DEAD;
        timer[i] = respawnSeconds;
        enemyOfAgent[agent[i]] = -1;
        sync.unbind(agent[i]);
        crowd.removeAgent(agent[i]);
        agent[i] = -1;
        animator.setAgent(character[i], -1);
        animator.die(character[i]);
        corpse[i] = CORPSE_SECONDS; // hidden once the body has lain a moment
        visible[i] = false;
        alive--;
    }

    private final float[] point = new float[3];

    private void respawn(int i, Party party) {
        float[] c = point;
        party.center(false, c);
        randomPoint(c[0], c[2], 18, c);
        int a = crowd.addAgent(c[0], c[1], c[2], params);
        if (a < 0) {
            timer[i] = 1;
            return;
        }
        agent[i] = a;
        enemyOfAgent[a] = i;
        if (!hidden[i]) {
            world.setGroupHidden(root[i], true); // the body vanishes; the fog shows it again
            hidden[i] = true;
        }
        corpse[i] = 0;
        animator.setAgent(character[i], a);
        animator.revive(character[i]);
        world.transforms().setPosition(root[i], c[0], c[1], c[2]);
        sync.bind(a, root[i], 0);
        hp[i] = MAX_HP;
        state[i] = IDLE;
        timer[i] = rng.nextFloat() * 2;
        homeX[i] = c[0];
        homeY[i] = c[1];
        homeZ[i] = c[2];
        alive++;
    }

    /** Shows the enemies the party can see and hides the rest (fog of war). */
    void updateVisibility(Visibility vis) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            boolean v = state[i] != DEAD && vis.isVisible(crowd.x(agent[i]), crowd.z(agent[i]));
            visible[i] = v;
            if (v) {
                n++;
            }
            if (state[i] != DEAD && hidden[i] == v) {
                hidden[i] = !v;
                world.setGroupHidden(root[i], !v);
            }
        }
        visibleCount = n;
    }

    /** Pushes outline changes: hostile while hovered or just hit. */
    void updateHighlights() {
        for (int i = 0; i < count; i++) {
            int s =
                    state[i] != DEAD && (hovered == i || flash[i] > 0)
                            ? RenderPacket.HIGHLIGHT_HOSTILE
                            : 0;
            if (s != style[i]) {
                style[i] = s;
                world.setGroupHighlight(root[i], s);
            }
        }
    }

    /**
     * Adds up to {@code budget} faint soul lights in the skeletons' eyes (the perf scene's light
     * load); returns how many.
     */
    int extractLights(RenderPacket p, int budget) {
        int added = 0;
        for (int i = 0; i < count && added < budget; i++) {
            if (state[i] == DEAD) {
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
        for (int attempt = 0; attempt < 16; attempt++) {
            int i = rng.nextInt(count);
            if (state[i] != DEAD && visible[i]) {
                out[0] = crowd.x(agent[i]);
                out[1] = crowd.y(agent[i]);
                out[2] = crowd.z(agent[i]);
                return true;
            }
        }
        return false;
    }
}
