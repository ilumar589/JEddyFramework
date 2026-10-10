package dev.jeddy.samples.iso;

import dev.jeddy.animation.Animations;
import dev.jeddy.animation.Clip;
import dev.jeddy.animation.Skeleton;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.crowd.Crowd;

/**
 * What the demo's characters look like they are doing (TASKS M12-07): each one's {@link Animations}
 * instance follows its crowd agent (idle, or walking and running blended by its speed, the playback
 * rate matched to it so feet do not slide) and plays one-shot actions on request (attacks, casts,
 * hit reactions), death (holding the last frame) and the skeletons' awakening.
 *
 * <p>The two KayKit rigs share one joint layout, so each animation library is baked once and every
 * character of the rig plays the same clips.
 *
 * <p>Thread ownership: the game thread. Allocates nothing after construction.
 */
final class Animator {
    /** Rig: the adventurers. */
    static final int HEROES = 0;

    /** Rig: the skeletons. */
    static final int SKELETONS = 1;

    /** Animation library per rig. */
    static final String[] LIBRARIES = {
        "characters/anims/heroes.jscene", "characters/anims/skeletons.jscene"
    };

    // Clip slots.
    static final int IDLE = 0;
    static final int WALK = 1;
    static final int RUN = 2;
    static final int ATTACK_1H = 3;
    static final int ATTACK_2H = 4;
    static final int ATTACK_DUAL = 5;
    static final int CAST = 6;
    static final int HIT = 7;
    static final int DEATH = 8;
    static final int COMBAT_IDLE = 9;
    static final int AWAKEN = 10;
    static final int CHEER = 11;
    private static final int SLOTS = 12;

    /** Clip names per rig and slot ({@code null}: use another slot, see {@link #clip}). */
    private static final String[][] NAMES = {
        {
            "Idle",
            "Walking_A",
            "Running_A",
            "1H_Melee_Attack_Chop",
            "2H_Melee_Attack_Chop",
            "Dualwield_Melee_Attack_Stab",
            "Spellcast_Shoot",
            "Hit_A",
            "Death_A",
            null,
            null,
            "Cheer"
        },
        {
            "Idle",
            "Walking_D_Skeletons",
            "Running_A",
            "1H_Melee_Attack_Chop",
            "2H_Melee_Attack_Chop",
            null,
            "Spellcast_Shoot",
            "Hit_A",
            "Death_A",
            "Idle_Combat",
            "Skeletons_Awaken_Standing",
            "Taunt"
        }
    };

    /**
     * Ground speed of the walk and run clips in model meters per second, measured on the feet in
     * contact with the ground (Gait analysis of the libraries); the world scale multiplies them.
     */
    private static final float[][] GAIT = {{1.12f, 3.57f}, {1.0f, 3.57f}};

    private static final float STAND = 0.15f;
    private static final float FADE = 0.2f;

    // Modes.
    private static final byte LOCOMOTION = 0;
    private static final byte ACTION = 1;
    private static final byte DEAD = 2;

    // Locomotion states.
    private static final byte NONE = 0;
    private static final byte STANDING = 1;
    private static final byte MOVING = 2;

    private final World world;
    private final Animations animations;
    private final Crowd crowd;
    private final Clip[][] clips = new Clip[2][SLOTS];
    private final float[] walkSpeed = new float[2];
    private final float[] runSpeed = new float[2];

    private int count;
    private final int[] instance;
    private final int[] rig;
    private final int[] agent;
    private final byte[] mode;
    private final byte[] moving;
    private final boolean[] combat;
    private final float[] actionLeft;

    /**
     * Bakes the rigs' clips (cold: allocates) for up to {@code capacity} characters scaled by
     * {@code scale}.
     *
     * @param libraries the animation library scene per rig ({@link #LIBRARIES})
     * @param skeletons a skeleton of each rig to bake against
     */
    Animator(
            World world,
            Crowd crowd,
            SceneData[] libraries,
            Skeleton[] skeletons,
            float scale,
            int capacity) {
        this.world = world;
        this.animations = world.animations();
        this.crowd = crowd;
        for (int r = 0; r < 2; r++) {
            for (int s = 0; s < SLOTS; s++) {
                String name = NAMES[r][s];
                if (name != null) {
                    // Locomotion plays in place: the crowd moves the character.
                    int lock = s == WALK || s == RUN ? skeletons[r].find("root") : -1;
                    clips[r][s] =
                            Clip.bake(
                                    libraries[r],
                                    animationIndex(libraries[r], name),
                                    skeletons[r],
                                    Clip.DEFAULT_RATE,
                                    lock);
                }
            }
            walkSpeed[r] = GAIT[r][0] * scale;
            runSpeed[r] = GAIT[r][1] * scale;
        }
        instance = new int[capacity];
        rig = new int[capacity];
        agent = new int[capacity];
        mode = new byte[capacity];
        moving = new byte[capacity];
        combat = new boolean[capacity];
        actionLeft = new float[capacity];
    }

    private static int animationIndex(SceneData library, String name) {
        for (int i = 0; i < library.animations().size(); i++) {
            if (library.animations().get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no clip " + name);
    }

    /** The clip of {@code slot} for rig {@code r}, falling back where a rig lacks one. */
    private Clip clip(int r, int slot) {
        Clip c = clips[r][slot];
        if (c != null) {
            return c;
        }
        return slot == ATTACK_DUAL ? clips[r][ATTACK_1H] : clips[r][IDLE];
    }

    /**
     * Registers the character spawned at {@code root} (an animated scene) driven by crowd agent
     * {@code a}; returns its index. It starts idle at a random point of the loop.
     */
    int add(int root, int r, int a, Rng rng) {
        int c = count++;
        instance[c] = world.animation(root);
        rig[c] = r;
        agent[c] = a;
        mode[c] = LOCOMOTION;
        moving[c] = NONE;
        locomotion(c);
        animations.setPhase(instance[c], rng.nextFloat());
        return c;
    }

    /** Points character {@code c} at another crowd agent (respawns), or -1 for none. */
    void setAgent(int c, int a) {
        agent[c] = a;
    }

    /** Makes character {@code c} stand in its combat stance (where the rig has one). */
    void setCombat(int c, boolean on) {
        if (combat[c] != on) {
            combat[c] = on;
            if (mode[c] == LOCOMOTION && moving[c] == STANDING) {
                moving[c] = NONE; // restart the idle with the other clip
            }
        }
    }

    /** Returns whether character {@code c} is free for an action (not acting and not dead). */
    boolean isFree(int c) {
        return mode[c] == LOCOMOTION;
    }

    /** Plays a one-shot action (attack, cast, hit), then returns to locomotion. */
    void action(int c, int slot) {
        if (mode[c] == DEAD) {
            return;
        }
        Clip clip = clip(rig[c], slot);
        animations.playOnce(instance[c], clip, 0.1f);
        mode[c] = ACTION;
        actionLeft[c] = clip.duration() - FADE;
        moving[c] = NONE;
    }

    /** Plays the death clip and holds its last frame until {@link #revive}. */
    void die(int c) {
        animations.playOnce(instance[c], clip(rig[c], DEATH), 0.1f);
        mode[c] = DEAD;
    }

    /** Brings a dead character back with its rig's awakening clip. */
    void revive(int c) {
        mode[c] = LOCOMOTION;
        action(c, AWAKEN);
    }

    /** Per frame: actions run out, locomotion follows the agents' speeds. Allocates nothing. */
    void update(float dt) {
        for (int c = 0; c < count; c++) {
            if (mode[c] == DEAD || !animations.isActive(instance[c])) {
                continue; // hidden characters keep their pose and their timers
            }
            if (mode[c] == ACTION) {
                actionLeft[c] -= dt;
                if (actionLeft[c] > 0) {
                    continue;
                }
                mode[c] = LOCOMOTION;
            }
            locomotion(c);
        }
    }

    private void locomotion(int c) {
        int a = agent[c];
        float speed = 0;
        if (a >= 0 && crowd.isActive(a)) {
            float vx = crowd.velocityX(a);
            float vz = crowd.velocityZ(a);
            speed = (float) Math.sqrt(vx * vx + vz * vz);
        }
        int r = rig[c];
        int i = instance[c];
        if (speed < STAND) {
            if (moving[c] != STANDING) {
                animations.play(i, clip(r, combat[c] ? COMBAT_IDLE : IDLE), FADE);
                moving[c] = STANDING;
            }
            return;
        }
        float walk = walkSpeed[r];
        float run = runSpeed[r];
        float w = Math.clamp((speed - walk) / (run - walk), 0f, 1f);
        float rate = Math.clamp(speed / (walk + (run - walk) * w), 0.5f, 2f);
        if (moving[c] != MOVING) {
            animations.play(i, clips[r][WALK], clips[r][RUN], w, rate, true, FADE);
            moving[c] = MOVING;
        } else {
            animations.setBlendWeight(i, w);
            animations.setSpeed(i, rate);
        }
    }
}
