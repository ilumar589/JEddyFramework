package dev.jeddy.components.sample;

import dev.jeddy.components.Behaviour;
import dev.jeddy.components.OnFixedUpdate;
import dev.jeddy.components.Optional;
import dev.jeddy.components.Without;

/** Sequential fixed-update behaviours. */
@Behaviour(order = 1)
public final class Regen {
    private Regen() {}

    /** One point a tick up to max, unless dead. */
    @OnFixedUpdate
    @Without({Dead.class})
    public static void regen(HealthMut h, float dt) {
        if (h.current() < h.max()) {
            h.setCurrent(h.current() + 1);
        }
    }

    /** Counts whether the entity had a tag; the tag is optional. */
    @OnFixedUpdate
    public static void count(HealthView h, @Optional TaggedView t, Counts counts, int entity) {
        if (t.present()) {
            counts.healedWithTag++;
        } else {
            counts.healedWithoutTag++;
        }
        counts.lastEntity = entity;
    }
}
