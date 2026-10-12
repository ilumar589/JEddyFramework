package dev.jeddy.samples.iso;

import dev.jeddy.app.world.AgentSync;
import dev.jeddy.app.world.World;
import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnEvent;
import dev.jeddy.components.OnFixedUpdate;
import dev.jeddy.components.OnUpdate;
import dev.jeddy.components.Parallel;
import dev.jeddy.components.ThreadSafe;
import dev.jeddy.math.Rng;
import dev.jeddy.physics.crowd.Crowd;
import dev.jeddy.physics.vis.Visibility;
import dev.jeddy.render.RenderPacket;

/**
 * The skeletons' behaviours (DESIGN §22, TASKS M22-07): what {@code Enemies.think} did per
 * skeleton, as static methods over the component views. {@link #think} runs in stage 0 before the
 * party and the crowd; {@link #see} and {@link #show} in stage 1 after the fog of war updated, the
 * first in parallel on the job system (it only reads the visibility grid and the crowd), the second
 * on the game thread because it changes the world; {@link #outline} runs per frame; {@link #flinch}
 * answers the {@link Signals#HURT} signal the combat raises.
 *
 * <p>Thread ownership: the game thread, except {@link #see} (job threads, disjoint rows).
 */
@Behaviour
public final class SkeletonAi {
    private SkeletonAi() {}

    /** One decision per tick: chase, strike, give up, lie dead, rise again. */
    @OnFixedUpdate(stage = 0)
    public static void think(
            MonsterView m,
            MindMut mind,
            SightView sight,
            FlashMut flash,
            float dt,
            int entity,
            Enemies enemies,
            Party party,
            Crowd crowd,
            Animator animator,
            AgentSync sync,
            Level level,
            Rng rng,
            World world) {
        Combat combat = enemies.combat;
        int i = m.index();
        if (flash.flash() > 0) {
            flash.setFlash(flash.flash() - dt);
        }
        mind.setCooldown(mind.cooldown() - dt);
        if (mind.state() == MonsterState.DEAD) {
            if (mind.corpse() > 0) {
                mind.setCorpse(mind.corpse() - dt);
                if (mind.corpse() <= 0 && !sight.hidden()) {
                    enemies.hide(i, true);
                }
            }
            mind.setTimer(mind.timer() - dt);
            if (enemies.respawnSeconds > 0 && mind.timer() <= 0) {
                enemies.respawn(i, party);
            }
            return;
        }
        int a = m.agent();
        float x = crowd.x(a);
        float z = crowd.z(a);
        int best = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int k = 0; k < Party.SIZE; k++) {
            if (party.down[k]) {
                continue; // the fallen are no threat and no target
            }
            int pa = party.agent[k];
            float dx = crowd.x(pa) - x;
            float dz = crowd.z(pa) - z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = pa;
            }
        }
        mind.setTimer(mind.timer() - dt);
        if (best < 0 && mind.state() != MonsterState.IDLE) {
            mind.setState(MonsterState.IDLE); // nobody left standing
            mind.setTimer(2);
            crowd.resetMoveTarget(a);
            combat.disengage(i);
        }
        switch (mind.state()) {
            case IDLE -> {
                if (best >= 0 && sight.visible() && bestD2 < Enemies.AGGRO * Enemies.AGGRO) {
                    mind.setState(MonsterState.CHASE);
                    mind.setTimer(0);
                    combat.engage(i);
                } else if (enemies.wander && mind.timer() <= 0) {
                    float[] w = level.walkable;
                    int o = rng.nextInt(level.walkableCount) * 3;
                    crowd.requestMoveTarget(a, w[o], w[o + 1], w[o + 2]);
                    mind.setTimer(5 + rng.nextFloat() * 6);
                }
            }
            case CHASE -> {
                if (bestD2 > Enemies.LEASH * Enemies.LEASH) {
                    mind.setState(MonsterState.IDLE);
                    mind.setTimer(2 + rng.nextFloat() * 4);
                    crowd.requestMoveTarget(a, mind.homeX(), mind.homeY(), mind.homeZ());
                    combat.disengage(i);
                } else if (combat.turnBasedMode) {
                    // The rules' AI moves it on its turn.
                } else if (bestD2 < Enemies.REACH * Enemies.REACH) {
                    mind.setState(MonsterState.ATTACK);
                    crowd.resetMoveTarget(a);
                } else if (mind.timer() <= 0) {
                    crowd.requestMoveTarget(a, crowd.x(best), crowd.y(best), crowd.z(best));
                    mind.setTimer(0.5f + (i % 8) * 0.05f);
                }
            }
            case ATTACK -> {
                if (combat.turnBasedMode) {
                    // The rules' AI strikes on its turn.
                } else if (bestD2 > (Enemies.REACH + 0.8f) * (Enemies.REACH + 0.8f)) {
                    mind.setState(MonsterState.CHASE);
                    mind.setTimer(0);
                } else if (mind.cooldown() <= 0
                        && sight.visible()
                        && animator.isFree(m.character())
                        && combat.canStrike(combat.enemyCreature(i))) {
                    sync.faceToward(a, crowd.x(best), crowd.z(best));
                    animator.action(m.character(), Characters.ENEMY_ATTACK[m.model()]);
                    mind.setCooldown(1.4f + rng.nextFloat() * 1.2f);
                    int member = party.memberOf(best);
                    if (member >= 0) {
                        combat.strike(combat.enemyCreature(i), combat.partyCreature(member));
                    }
                }
            }
            default -> {}
        }
        animator.setCombat(m.character(), mind.state() != MonsterState.IDLE);
    }

    /**
     * Whether the party sees the skeleton: a read of the visibility grid at its position, in
     * parallel (300 skeletons, independent rows).
     */
    @OnFixedUpdate(stage = 1)
    @Parallel(grain = 32)
    public static void see(
            MonsterView m,
            MindView mind,
            SightMut sight,
            @ThreadSafe Visibility vis,
            @ThreadSafe Crowd crowd) {
        boolean v =
                mind.state() != MonsterState.DEAD
                        && vis.isVisible(crowd.x(m.agent()), crowd.z(m.agent()));
        sight.setVisible(v);
    }

    /** Applies the visibility to the world (hidden groups) and counts the visible. */
    @OnFixedUpdate(stage = 1)
    public static void show(MonsterView m, MindView mind, SightMut sight, int entity, World world) {
        if (mind.state() != MonsterState.DEAD && sight.hidden() == sight.visible()) {
            sight.setHidden(!sight.visible());
            world.setGroupHidden(entity, !sight.visible());
        }
    }

    /** Pushes outline changes once per frame: hostile while hovered or just hit. */
    @OnUpdate
    public static void outline(
            MonsterView m,
            MindView mind,
            FlashMut flash,
            int entity,
            Enemies enemies,
            World world) {
        int s =
                mind.state() != MonsterState.DEAD
                                && (enemies.hovered == m.index() || flash.flash() > 0)
                        ? RenderPacket.HIGHLIGHT_HOSTILE
                        : 0;
        if (s != flash.style()) {
            flash.setStyle(s);
            world.setGroupHighlight(entity, s);
        }
    }

    /** Hurt by the rules (the combat raises the signal): flash, flinch, wake up. */
    @OnEvent(Signals.HURT)
    public static void flinch(
            MonsterView m, MindMut mind, FlashMut flash, Enemies enemies, Animator animator) {
        if (mind.state() == MonsterState.DEAD) {
            return;
        }
        flash.setFlash(0.35f);
        if (mind.state() == MonsterState.IDLE) {
            mind.setState(MonsterState.CHASE);
            mind.setTimer(0);
            enemies.combat.engage(m.index());
        }
        animator.action(m.character(), Animator.HIT);
    }
}
