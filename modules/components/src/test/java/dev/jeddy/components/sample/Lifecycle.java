package dev.jeddy.components.sample;

import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnDespawn;
import dev.jeddy.components.OnEvent;
import dev.jeddy.components.OnSpawn;
import dev.jeddy.components.OnTrigger;
import dev.jeddy.components.OnTurn;

/** The per-entity phases. */
@Behaviour
public final class Lifecycle {
    /** A signal kind the game defines. */
    public static final int HURT = 7;

    private Lifecycle() {}

    @OnSpawn
    public static void spawned(HealthMut h, Counts counts) {
        counts.spawned++;
        h.setCurrent(h.max());
    }

    @OnDespawn
    public static void despawned(HealthView h, Counts counts) {
        counts.despawned++;
    }

    @OnTurn
    public static void turn(HealthView h, Counts counts) {
        counts.turns++;
    }

    @OnTrigger(OnTrigger.ENTER)
    public static void entered(PositionView p, Counts counts, int trigger, int kind) {
        counts.entered++;
        counts.lastTrigger = trigger * 10 + kind;
    }

    @OnTrigger(OnTrigger.EXIT)
    public static void exited(PositionView p, Counts counts) {
        counts.exited++;
    }

    @OnEvent(HURT)
    public static void hurt(HealthMut h, Counts counts, int a, int b) {
        counts.signalled++;
        counts.lastA = a;
        counts.lastB = b;
        h.setCurrent(h.current() - a);
    }
}
