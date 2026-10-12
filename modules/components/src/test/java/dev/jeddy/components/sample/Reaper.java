package dev.jeddy.components.sample;

import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnFixedUpdate;

/** Removes the visited entity's components when its health is gone (a structural change). */
@Behaviour(order = 2)
public final class Reaper {
    private Reaper() {}

    @OnFixedUpdate
    public static void reap(HealthView h, SampleGame game, int entity) {
        if (h.current() <= 0) {
            game.despawn(entity);
        }
    }
}
