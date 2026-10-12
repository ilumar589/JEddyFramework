package dev.jeddy.components.sample;

import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnFixedUpdate;
import dev.jeddy.components.OnUpdate;
import dev.jeddy.components.Parallel;

/** Parallel behaviours: two writers of Position (dependent), one of Health (independent). */
@Behaviour(order = 3)
public final class Movers {
    private Movers() {}

    @OnFixedUpdate
    @Parallel(grain = 2)
    public static void turnLeft(PositionMut p, float dt) {
        p.setYaw(p.yaw() + dt);
    }

    @OnFixedUpdate
    @Parallel(grain = 2)
    public static void rise(PositionMut p, HealthView h, float dt) {
        p.setPosY(p.posY() + dt * h.current());
    }

    @OnFixedUpdate
    @Parallel(grain = 2)
    public static void bleed(HealthMut h) {
        h.setMax(h.max() + 1);
    }

    @OnUpdate
    @Parallel(grain = 3)
    public static void drift(PositionMut p, float dt, float alpha) {
        p.setPosX(p.posX() + dt * alpha);
    }
}
