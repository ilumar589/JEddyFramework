package dev.jeddy.samples.iso;

import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.World;
import dev.jeddy.asset.format.SceneData;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.crowd.AgentParams;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.crowd.Formation;
import dev.jeddy.physics.crowd.FormationMove;
import dev.jeddy.render.RenderPacket;

/**
 * The player's six adventurers (TASKS M10-03): selection by click, box and keys, highlight
 * outlines, and move orders, alone or in one of a few formations with paced walking. Standing
 * members strike at skeletons within reach when the rules give them an attack ({@link Combat},
 * TASKS M15-07); a member the rules put down (dying, unconscious) takes no orders until healed.
 *
 * <p>Thread ownership: the game thread. Allocates nothing after construction.
 */
final class Party {
    static final int SIZE = 6;
    static final float SIGHT = 14;

    /** Members that cannot act (dying, unconscious, dead); kept by {@link #updateDown}. */
    final boolean[] down = new boolean[SIZE];

    /** The rules; set once the party and the skeletons exist. */
    Combat combat;

    /** Formation names for the HUD, upper case for the debug font. */
    static final String[] FORMATION_NAMES = {"WEDGE", "BOX", "LINE", "COLUMN", "CIRCLE"};

    final int[] agent = new int[SIZE];
    final int[] root = new int[SIZE];
    final int[] character = new int[SIZE]; // Animator index
    final boolean[] selected = new boolean[SIZE];
    final int[] selection = new int[SIZE];
    int selectionCount;
    int hovered = -1;
    int formation;

    private final Crowd crowd;
    private final World world;
    private final FormationMove mover;
    private final Formation[] formations = {
        Formation.wedge(SIZE, 1.7f),
        Formation.box(SIZE, 3, 1.6f),
        Formation.line(SIZE, 1.5f),
        Formation.column(SIZE, 1.6f),
        Formation.circle(SIZE, 2.2f)
    };
    private final int[] style = new int[SIZE];
    private final float[] cooldown = new float[SIZE];
    private final Animator animator;
    private final AgentSync sync;
    private final Rng rng;

    Party(
            Level level,
            World world,
            AgentSync sync,
            SceneData[] models,
            Animator animator,
            Rng rng) {
        this.crowd = level.crowd;
        this.world = world;
        this.animator = animator;
        this.sync = sync;
        this.rng = rng;
        mover = new FormationMove(crowd, level.query, SIZE);
        AgentParams params = new AgentParams();
        params.radius = 0.4f;
        params.maxSpeed = 3.6f;
        params.maxAcceleration = 14f;
        params.separationWeight = 1.5f;
        // Start in a box around the first party spawn point, facing +x (into the crypt).
        float cx = level.partySpawns[0] + 2;
        float cz = level.partySpawns[2] + 2;
        float[] p = new float[3];
        for (int i = 0; i < SIZE; i++) {
            float x = cx + (i % 3 - 1) * 1.6f;
            float z = cz + (i / 3 - 0.5f) * 1.8f;
            if (!level.snap(x, 0, z, 2f, p)) {
                level.snap(cx, 0, cz, 4f, p);
            }
            agent[i] = crowd.addAgent(p[0], p[1], p[2], params);
            root[i] = Characters.spawn(world, models[i], p[0], p[1], p[2], Characters.FACE_PLUS_X);
            sync.bind(agent[i], root[i], Characters.FACE_PLUS_X);
            character[i] = animator.add(root[i], Animator.HEROES, agent[i], rng);
        }
        select(0, false);
    }

    /** Returns the party member of crowd agent {@code a}, or -1. */
    int memberOf(int a) {
        for (int i = 0; i < SIZE; i++) {
            if (agent[i] == a) {
                return i;
            }
        }
        return -1;
    }

    void select(int member, boolean additive) {
        if (!additive) {
            for (int i = 0; i < SIZE; i++) {
                selected[i] = false;
            }
        }
        selected[member] = !additive || !selected[member];
        rebuildSelection();
    }

    void selectAll() {
        for (int i = 0; i < SIZE; i++) {
            selected[i] = true;
        }
        rebuildSelection();
    }

    /**
     * Selects the members whose bodies project into the window rectangle (window coordinates),
     * using the camera's view-projection {@code vp} (column-major).
     */
    void selectBox(
            float[] vp,
            float windowWidth,
            float windowHeight,
            float minX,
            float minY,
            float maxX,
            float maxY,
            boolean additive) {
        boolean any = false;
        for (int i = 0; i < SIZE; i++) {
            int a = agent[i];
            float x = crowd.x(a);
            float y = crowd.y(a) + 0.9f;
            float z = crowd.z(a);
            float cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15];
            if (cw <= 0) {
                continue;
            }
            float sx = ((vp[0] * x + vp[4] * y + vp[8] * z + vp[12]) / cw + 1) * 0.5f * windowWidth;
            float sy =
                    (1 - (vp[1] * x + vp[5] * y + vp[9] * z + vp[13]) / cw) * 0.5f * windowHeight;
            boolean inside = sx >= minX && sx <= maxX && sy >= minY && sy <= maxY;
            if (inside && !any && !additive) {
                for (int k = 0; k < SIZE; k++) {
                    selected[k] = false;
                }
            }
            if (inside) {
                selected[i] = true;
                any = true;
            }
        }
        rebuildSelection();
    }

    private void rebuildSelection() {
        selectionCount = 0;
        for (int i = 0; i < SIZE; i++) {
            if (selected[i] && !down[i]) {
                selection[selectionCount++] = agent[i];
            }
        }
    }

    /** Refreshes who is down (once per tick): the fallen stop walking and leave the orders. */
    void updateDown() {
        boolean changed = false;
        for (int i = 0; i < SIZE; i++) {
            boolean d = !combat.canAct(combat.partyCreature(i));
            if (d != down[i]) {
                down[i] = d;
                changed = true;
                if (d) {
                    crowd.resetMoveTarget(agent[i]);
                }
            }
        }
        if (changed) {
            rebuildSelection();
        }
    }

    /** First selected member, or -1. */
    int leader() {
        for (int i = 0; i < SIZE; i++) {
            if (selected[i]) {
                return i;
            }
        }
        return -1;
    }

    void cycleFormation() {
        formation = (formation + 1) % formations.length;
    }

    /** Selects a formation by index ({@link #FORMATION_NAMES}). */
    void setFormation(int index) {
        formation = Math.clamp(index, 0, formations.length - 1);
    }

    /** Sends the selection to {@code (x, y, z)}: one member walks there, a group forms up. */
    void order(float x, float y, float z) {
        if (selectionCount == 0) {
            return;
        }
        if (selectionCount == 1) {
            mover.release();
            crowd.requestMoveTarget(selection[0], x, y, z);
            return;
        }
        mover.order(selection, selectionCount, formations[formation], x, y, z, 0, 0);
    }

    /** Orders everyone (scripted runs). */
    void orderAll(float x, float y, float z) {
        for (int i = 0; i < SIZE; i++) {
            selected[i] = true;
        }
        rebuildSelection();
        order(x, y, z);
    }

    /** Paces formation members (once per tick). */
    void tick() {
        mover.update();
    }

    /**
     * Once per tick in real time: members standing next to a living skeleton turn to it and swing
     * when the rules give them an attack this round; the casters only cast on order ({@link
     * #cast}). In turn-based mode the player orders every strike ({@link #strikeAt}).
     */
    void fight(float dt, Enemies enemies) {
        for (int i = 0; i < SIZE; i++) {
            cooldown[i] -= dt;
            int c = character[i];
            int attack = Characters.PARTY_ATTACK[i];
            if (attack == Animator.CAST
                    || cooldown[i] > 0
                    || down[i]
                    || combat.turnBasedMode
                    || !animator.isFree(c)) {
                continue;
            }
            int a = agent[i];
            float vx = crowd.velocityX(a);
            float vz = crowd.velocityZ(a);
            if (vx * vx + vz * vz > 0.04f) {
                continue;
            }
            int target = enemies.nearestAlive(crowd.x(a), crowd.z(a), Enemies.REACH + 0.4f);
            if (target >= 0 && combat.canStrike(combat.partyCreature(i))) {
                sync.faceToward(a, crowd.x(target), crowd.z(target));
                animator.action(c, attack);
                cooldown[i] = 0.9f + rng.nextFloat() * 0.8f;
                combat.strike(
                        combat.partyCreature(i), combat.enemyCreature(enemies.enemyOf(target)));
            }
        }
    }

    /**
     * Member {@code i} strikes skeleton {@code enemy} on the player's order (turn-based mode):
     * returns whether the rules allowed a swing.
     */
    boolean strikeAt(int i, Enemies enemies, int enemy) {
        int pc = combat.partyCreature(i);
        if (down[i] || !combat.canStrike(pc)) {
            return false;
        }
        int a = agent[i];
        int ea = enemies.agent(enemy);
        float dx = crowd.x(ea) - crowd.x(a);
        float dz = crowd.z(ea) - crowd.z(a);
        if (dx * dx + dz * dz > (Enemies.REACH + 0.4f) * (Enemies.REACH + 0.4f)) {
            return false;
        }
        sync.faceToward(a, crowd.x(ea), crowd.z(ea));
        int attack = Characters.PARTY_ATTACK[i];
        animator.action(character[i], attack == Animator.CAST ? Animator.ATTACK_1H : attack);
        int ec = combat.enemyCreature(enemy);
        int swings = 0;
        while (combat.canStrike(pc) && swings < 6) {
            combat.strike(pc, ec);
            swings++;
        }
        return swings > 0;
    }

    /** Member {@code i} casts toward {@code (x, z)}. */
    void cast(int i, float x, float z) {
        sync.faceToward(agent[i], x, z);
        animator.action(character[i], Animator.CAST);
    }

    /** Average position of the party (or of the selection, if {@code selectionOnly}). */
    void center(boolean selectionOnly, float[] out) {
        float x = 0;
        float y = 0;
        float z = 0;
        int n = 0;
        for (int i = 0; i < SIZE; i++) {
            if (selectionOnly && !selected[i]) {
                continue;
            }
            x += crowd.x(agent[i]);
            y += crowd.y(agent[i]);
            z += crowd.z(agent[i]);
            n++;
        }
        if (n == 0) {
            center(false, out);
            return;
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
    }

    /** Pushes outline styles that changed (selected beats hovered). */
    void updateHighlights() {
        for (int i = 0; i < SIZE; i++) {
            int s =
                    selected[i]
                            ? RenderPacket.HIGHLIGHT_SELECTED
                            : hovered == i ? RenderPacket.HIGHLIGHT_HOVER : 0;
            if (s != style[i]) {
                style[i] = s;
                world.setGroupHighlight(root[i], s);
            }
        }
    }

    /** Selection rings under the selected members. */
    void extract(RenderPacket p) {
        for (int i = 0; i < SIZE; i++) {
            if (selected[i]) {
                int a = agent[i];
                p.addSelectionCircle(
                        crowd.x(a), crowd.y(a), crowd.z(a), 0.65f, 0.3f, 1f, 0.45f, 0.9f);
            }
        }
    }
}
